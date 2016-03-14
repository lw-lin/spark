/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution.streaming.state

import java.util.{Timer, TimerTask}

import scala.collection.mutable
import scala.reflect.ClassTag
import scala.util.Random
import scala.util.control.NonFatal

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileStatus, Path}

import org.apache.spark.{Logging, SparkConf, SparkEnv}
import org.apache.spark.serializer.{DeserializationStream, KryoSerializer, SerializationStream}
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.JoinedRow
import org.apache.spark.util.{CompletionIterator, RpcUtils, Utils}

case class StateStoreId(operatorId: Long, partitionId: Int)

private[state] object StateStore extends Logging {

  sealed trait StoreUpdate
  case class ValueUpdated(key: InternalRow, value: InternalRow) extends StoreUpdate
  case class KeyRemoved(key: InternalRow) extends StoreUpdate

  private val loadedStores = new mutable.HashMap[StateStoreId, StateStore]()
  private val managementTimer = new Timer("StateStore Timer", true)
  @volatile private var managementTask: TimerTask = null

  def get(storeId: StateStoreId, directory: String): StateStore = {
    val store = loadedStores.synchronized {
      startIfNeeded()
      loadedStores.getOrElseUpdate(storeId, new StateStore(storeId, directory))
    }
    reportActiveInstance(storeId)
    store
  }

  def clearAll(): Unit = loadedStores.synchronized {
    loadedStores.clear()
    if (managementTask != null) {
      managementTask.cancel()
      managementTask = null
    }
  }

  private def remove(storeId: StateStoreId): Unit = {
    loadedStores.remove(storeId)
  }

  private def reportActiveInstance(storeId: StateStoreId): Unit = {
    val host = SparkEnv.get.blockManager.blockManagerId.host
    val executorId = SparkEnv.get.blockManager.blockManagerId.executorId
    askCoordinator[Boolean](ReportActiveInstance(storeId, host, executorId))
  }

  private def verifyIfInstanceActive(storeId: StateStoreId): Boolean = {
    val executorId = SparkEnv.get.blockManager.blockManagerId.executorId
    askCoordinator[Boolean](VerifyIfInstanceActive(storeId, executorId)).getOrElse(false)
  }

  private def askCoordinator[T: ClassTag](message: StateStoreCoordinatorMessage): Option[T] = {
    try {
      val env = SparkEnv.get
      if (env != null) {
        val coordinatorRef = RpcUtils.makeDriverRef("StateStoreCoordinator", env.conf, env.rpcEnv)
        Some(coordinatorRef.askWithRetry[T](message))
      } else {
        None
      }
    } catch {
      case NonFatal(e) =>
        clearAll()
        None
    }
  }

  private def startIfNeeded(): Unit = loadedStores.synchronized {
    if (managementTask == null) {
      managementTask = new TimerTask {
        override def run(): Unit = { manageFiles() }
      }
      managementTimer.schedule(managementTask, 10000, 10000)
    }
  }

  private def manageFiles(): Unit = {
    loadedStores.synchronized { loadedStores.values.toSeq }.foreach { store =>
      try {
        store.manageFiles()
      } catch {
        case NonFatal(e) =>
          logWarning(s"Error performing snapshot and cleaning up store ${store.id}")
      }
    }
  }
}

private[sql] class StateStore(
    val id: StateStoreId,
    val directory: String,
    numBatchesToRetain: Int = 2,
    maxDeltaChainForSnapshots: Int = 10
  ) extends Logging {
  type MapType = mutable.HashMap[InternalRow, InternalRow]

  import StateStore._

  private val loadedMaps = new mutable.HashMap[Long, MapType]
  private val baseDir = new Path(directory, s"${id.operatorId}/${id.partitionId.toString}")
  private val fs = baseDir.getFileSystem(new Configuration())
  private val serializer = new KryoSerializer(new SparkConf)

  /**
   * Thread local variable to keep track of updates so that if there multiple speculative tasks
   * in the same executor trying to update the same store, the updates are thread-safe.
   */
  private val uncommittedDelta = new ThreadLocal[UncommittedUpdates]() {
    override def initialValue(): UncommittedUpdates = new UncommittedUpdates()
  }

  initialize()

  /**
   * Prepare for updates to create a new `version` of the map. The store ensure that updates
   * are made on the `version - 1` of the store data. If `version` already exists, it will
   * be overwritten when the updates are committed.
   */
  private[state] def prepareForUpdates(version: Long): Unit = synchronized {
    require(version >= 0, "Version cannot be less than 0")
    val newMap = new MapType()
    if (version > 0) {
      newMap ++= loadMap(version - 1)
    }
    uncommittedDelta.get.prepare(version, newMap)
  }

  /** Update the value of a key using the value generated by the update function */
  def update(key: InternalRow, updateFunc: Option[InternalRow] => InternalRow): Unit = {
    uncommittedDelta.get.update(key, updateFunc)
  }

  /** Remove keys that match the following condition */
  def remove(condition: InternalRow => Boolean): Unit = {
    uncommittedDelta.get.remove(condition)
  }

  /** Commit all the updates that have been made to the store. */
  def commitUpdates(): Unit = {
    uncommittedDelta.get.commit()
  }

  /** Cancel all the updates that have been made to the store. */
  def cancelUpdates(): Unit = {
    uncommittedDelta.get.reset()
  }

  def lastCommittedData(): Iterator[InternalRow] = {
    uncommittedDelta.get.lastCommittedData()
  }

  def lastCommittedUpdates(): Iterator[StoreUpdate] = {
    uncommittedDelta.get.lastCommittedUpdates()
  }

  private[state] def hasUncommittedUpdates: Boolean = {
    uncommittedDelta.get.hasUncommittedUpdates
  }

  override def toString(): String = {
    s"StateStore[id = (op=${id.operatorId},part=${id.partitionId}), dir = $baseDir]"
  }

  // Internal classes and methods

  private case class StoreFile(version: Long, path: Path, isSnapshot: Boolean)

  private class UncommittedUpdates {

    trait State
    case object INITIALIZED extends State
    case object PREPARED extends State
    case object COMMITTED extends State

    private var finalDeltaFile: Path = null
    private var tempDeltaFile: Path = null
    private var tempDeltaFileStream: SerializationStream = null
    private var updatedMap: MapType = null
    private var updateVersion: Long = -1
    private var state: State = INITIALIZED

    /**
     * Prepare the set updates to be made to the state store by setting the version and the initial
     * map on which to apply the updates.
     */
    def prepare(version: Long, map: MapType): Unit = {
      reset()
      updateVersion = version
      updatedMap = map
      finalDeltaFile = deltaFile(updateVersion)
      tempDeltaFile = new Path(baseDir, s"temp-${Random.nextLong}")
      tempDeltaFileStream = serializer.newInstance().serializeStream(fs.create(tempDeltaFile, true))
      state = PREPARED
    }

    /** Update the value of a key using the value generated by the update function */
    def update(key: InternalRow, updateFunc: Option[InternalRow] => InternalRow): Unit = {
      verify(state == PREPARED, "Cannot call update() before calling startUpdates()")
      val value = updateFunc(updatedMap.get(key))
      updatedMap.put(key, value)
      tempDeltaFileStream.writeObject(ValueUpdated(key, value))
    }

    /** Remove keys that match the following condition */
    def remove(condition: InternalRow => Boolean): Unit = {
      verify(state == PREPARED, "Cannot call remove() before calling startUpdates()")
      val keyIter = updatedMap.keysIterator
      while (keyIter.hasNext) {
        val key = keyIter.next
        if (condition(key)) {
          updatedMap.remove(key)
          tempDeltaFileStream.writeObject(KeyRemoved(key))
        }
      }
    }

    /** Commit all the updates that have been made to the store. */
    def commit(): Unit = {
      verify(state == PREPARED, "Cannot call commitUpdates() before calling prepareForUpdates()")
      try {
        tempDeltaFileStream.close()

        StateStore.this.synchronized {
          fs.rename(tempDeltaFile, finalDeltaFile)
          loadedMaps.put(updateVersion, updatedMap)
        }
        state = COMMITTED
      } catch {
        case NonFatal(e) =>
          state = INITIALIZED
          throw new IllegalStateException(
            s"Error committing version $updateVersion into $this", e)
      }
    }

    def reset(): Unit = {
      if (tempDeltaFileStream != null) {
        tempDeltaFileStream.close()
      }
      if (tempDeltaFile != null && fs.exists(tempDeltaFile)) {
        fs.delete(tempDeltaFile, true)
      }
      state = INITIALIZED
    }

    def lastCommittedData(): Iterator[InternalRow] = {
      verify(state == COMMITTED, "Cannot get iterator of data before calling commitUpdate()")
      StateStore.this.iterator(updateVersion)
    }

    def lastCommittedUpdates(): Iterator[StoreUpdate] = {
      verify(state == COMMITTED, "Cannot get iterator of updates before calling commitUpdate()")
      readDeltaFile(finalDeltaFile)
    }

    def hasUncommittedUpdates: Boolean = {
      state == PREPARED
    }
  }

  private def initialize(): Unit = {
    if (!fs.exists(baseDir)) {
      fs.mkdirs(baseDir)
    } else {
      if (!fs.isDirectory(baseDir)) {
        throw new IllegalStateException(
          s"Cannot use $directory for storing state data as" +
            s"$baseDir already exists and is not a directory")
      }
    }
  }

  /**
   * Get all the data of the latest version of the store.
   * Note that this will look up the files to determined the latest known version.
   */
  private[state] def latestIterator(): Iterator[InternalRow] = synchronized {
    val versionsInFiles = fetchFiles().map(_.version).toSet
    val versionsLoaded = loadedMaps.keySet
    val allKnownVersions = versionsInFiles ++ versionsLoaded
    if (allKnownVersions.nonEmpty) {
      loadMap(allKnownVersions.max)
        .iterator
        .map { case (key, value) => new JoinedRow(key, value) }
    } else Iterator.empty
  }

  private[state] def iterator(version: Long): Iterator[InternalRow] = synchronized {
    loadMap(version)
      .iterator
      .map { case (key, value) => new JoinedRow(key, value) }
  }


  private def loadMap(version: Long): MapType = {
    if (version < 0) return new MapType
    synchronized { loadedMaps.get(version) }.getOrElse {
      val mapFromFile = readSnapshotFile(version).getOrElse {
        val prevMap = loadMap(version - 1)
        val deltaUpdates = readDeltaFile(version)
        val newMap = new MapType()
        newMap ++= prevMap
        newMap.sizeHint(prevMap.size)
        while (deltaUpdates.hasNext) {
          deltaUpdates.next match {
            case ValueUpdated(key, value) => newMap.put(key, value)
            case KeyRemoved(key) => newMap.remove(key)
          }
        }
        newMap
      }
      loadedMaps.put(version, mapFromFile)
      mapFromFile
    }
  }

  private def readDeltaFile(version: Long): Iterator[StoreUpdate] = {
    readDeltaFile(deltaFile(version))
  }

  private def readDeltaFile (fileToRead: Path): Iterator[StoreUpdate] = {
    if (!fs.exists(fileToRead)) {
      throw new IllegalStateException(
        s"Cannot read delta file $fileToRead of $this: $fileToRead does not exist")
    }
    val deser = serializer.newInstance()
    var deserStream: DeserializationStream = null
    deserStream = deser.deserializeStream(fs.open(fileToRead))
    val iter = deserStream.asIterator.asInstanceOf[Iterator[StoreUpdate]]
    CompletionIterator[StoreUpdate, Iterator[StoreUpdate]](iter, { deserStream.close() })
  }

  private def writeSnapshotFile(version: Long, map: MapType): Unit = {
    val fileToWrite = snapshotFile(version)
    val ser = serializer.newInstance()
    var outputStream: SerializationStream = null
    Utils.tryWithSafeFinally {
      outputStream = ser.serializeStream(fs.create(fileToWrite, false))
      outputStream.writeAll(map.iterator)
    } {
      if (outputStream != null) outputStream.close()
    }
  }

  private def readSnapshotFile(version: Long): Option[MapType] = {
    val fileToRead = snapshotFile(version)
    if (!fs.exists(fileToRead)) return None

    val deser = serializer.newInstance()
    val map = new MapType()
    var deserStream: DeserializationStream = null

    try {
      deserStream = deser.deserializeStream(fs.open(fileToRead))
      val iter = deserStream.asIterator.asInstanceOf[Iterator[(InternalRow, InternalRow)]]
      while(iter.hasNext) {
        map += iter.next()
      }
      Some(map)
    } finally {
      if (deserStream != null) deserStream.close()
    }
  }

  private[state] def manageFiles(): Unit = {
    doSnapshot()
    cleanup()
  }

  private def doSnapshot(): Unit = {
    try {
      val files = fetchFiles()
      if (files.nonEmpty) {
        val lastVersion = files.last.version
        val deltaFilesForLastVersion =
          filesForVersion(files, lastVersion).filter(_.isSnapshot == false)
        synchronized {
          loadedMaps.get(lastVersion)
        } match {
          case Some(map) =>
            if (deltaFilesForLastVersion.size > maxDeltaChainForSnapshots) {
              writeSnapshotFile(lastVersion, map)
            }
          case None =>
          // The last map is not loaded, probably some other instance is incharge
        }

      }
    } catch {
      case NonFatal(e) =>
        logWarning(s"Error doing snapshots for $this")
    }
  }

  private[state] def cleanup(): Unit = {
    try {
      val files = fetchFiles()
      if (files.nonEmpty) {
        val earliestVersionToRetain = files.last.version - numBatchesToRetain
        if (earliestVersionToRetain >= 0) {
          val earliestFileToRetain = filesForVersion(files, earliestVersionToRetain).head
          synchronized {
            loadedMaps.keys.filter(_ < earliestVersionToRetain).foreach(loadedMaps.remove)
          }
          files.filter(_.version < earliestFileToRetain.version).foreach { f =>
            fs.delete(f.path, true)
          }
        }
      }
    } catch {
      case NonFatal(e) =>
        logWarning(s"Error cleaning up files for $this")
    }
  }

  private def filesForVersion(allFiles: Seq[StoreFile], version: Long): Seq[StoreFile] = {
    require(version >= 0)
    require(allFiles.exists(_.version == version))

    val latestSnapshotFileBeforeVersion = allFiles
      .filter(_.isSnapshot == true)
      .takeWhile(_.version <= version)
      .lastOption

    val deltaBatchFiles = latestSnapshotFileBeforeVersion match {
      case Some(snapshotFile) =>
        val deltaBatchIds = (snapshotFile.version + 1) to version

        val deltaFiles = allFiles.filter { file =>
          file.version > snapshotFile.version && file.version <= version
        }
        verify(
          deltaFiles.size == version - snapshotFile.version,
          s"Unexpected list of delta files for version $version: ${deltaFiles.mkString(",")}"
        )
        deltaFiles

      case None =>
        allFiles.takeWhile(_.version <= version)
    }
    latestSnapshotFileBeforeVersion.toSeq ++ deltaBatchFiles
  }

  private def fetchFiles(): Seq[StoreFile] = {
    val files: Seq[FileStatus] = try {
      fs.listStatus(baseDir)
    } catch {
      case _: java.io.FileNotFoundException =>
        Seq.empty
    }
    val versionToFiles = new mutable.HashMap[Long, StoreFile]
    files.foreach { status =>
      val path = status.getPath
      val nameParts = path.getName.split("\\.")
      if (nameParts.size == 2) {
        val version = nameParts(0).toLong
        nameParts(1).toLowerCase match {
          case "delta" =>
            // ignore the file otherwise, snapshot file already exists for that batch id
            if (!versionToFiles.contains(version)) {
              versionToFiles.put(version, StoreFile(version, path, isSnapshot = false))
            }
          case "snapshot" =>
            versionToFiles.put(version, StoreFile(version, path, isSnapshot = true))
          case _ =>
            logWarning(s"Could not identify file $path")
        }
      }
    }
    versionToFiles.values.toSeq.sortBy(_.version)
  }

  private def deltaFile(version: Long): Path = {
    new Path(baseDir, s"$version.delta")
  }

  private def snapshotFile(version: Long): Path = {
    new Path(baseDir, s"$version.snapshot")
  }

  private def verify(condition: => Boolean, msg: String): Unit = {
    if (!condition) {
      throw new IllegalStateException(msg)
    }
  }
}
