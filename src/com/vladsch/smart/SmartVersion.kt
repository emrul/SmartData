/*
 * Copyright (c) 2015-2016 Vladimir Schneider <vladimir.schneider@gmail.com>
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.vladsch.smart

import java.util.*

object SmartVersionManager {
    private var mySerial = Integer.MIN_VALUE + 1

    class GroupedUpdate : ThreadLocal<GroupedUpdate>() {
        var myInGroup = 0
        var myFrozenSerial = 0
    }

    private var groupedUpdate = GroupedUpdate()

    val isGrouped: Boolean get() = groupedUpdate.myInGroup > 0

    private val currentVersionRaw: Int
        get() {
            synchronized (this) {
                return mySerial
            }
        }

    val currentVersion: Int
        get() {
            return if (groupedUpdate.myInGroup > 0) groupedUpdate.myFrozenSerial
            else currentVersionRaw
        }

    val nextVersion: Int
        get() {
            if (groupedUpdate.myInGroup > 0) return groupedUpdate.myFrozenSerial
            return nextVersionRaw
        }

    private val nextVersionRaw: Int
        get() {
            synchronized (this) {
                if (mySerial == MAX_SERIAL) {
                    // roll over, should not happen under normal conditions
                    mySerial = MIN_SERIAL
                    return mySerial
                } else {
                    return ++mySerial
                }
            }
        }

    fun groupedUpdate(runnable: () -> Unit) {
        enterGroupedUpdate()
        try {
            runnable()
        } finally {
            leaveGroupedUpdate()
        }
    }

    fun groupedUpdate(runnable: Runnable) {
        enterGroupedUpdate()
        try {
            runnable.run()
        } finally {
            leaveGroupedUpdate()
        }
    }

    private fun leaveGroupedUpdate() {
        groupedUpdate.myInGroup--
    }

    private fun enterGroupedUpdate() {
        var wasGroupedUpdate = groupedUpdate.myInGroup++ > 0
        if (!wasGroupedUpdate) {
            // that way all updated data will be the latest version
            groupedUpdate.myFrozenSerial = nextVersionRaw
        }
    }

    fun <V> groupedCompute(dataComputable: () -> V): V {
        enterGroupedUpdate()
        try {
            return dataComputable()
        } finally {
            leaveGroupedUpdate()
        }
    }

    fun <V> groupedCompute(dataComputable: DataComputable<V>): V {
        enterGroupedUpdate()
        try {
            return dataComputable.compute()
        } finally {
            leaveGroupedUpdate()
        }
    }

    fun isStale(dependencies: Iterable<SmartVersion>, snapshotSerial: Int, dependenciesSerial: Int): Boolean {
        // see if last evaluation is current
        return groupedCompute(DataComputable {
            if (snapshotSerial >= currentVersion) return@DataComputable false

            // if last snapshot is less than the version serial then snapshot needs to be computed
            if (snapshotSerial < dependenciesSerial) return@DataComputable true

            // version snapshot < dependencies snapshot, see if any of the dependencies changed their version
            for (dependency in dependencies) {
                // if dependency is stale, then this snapshot is stale
                if (dependency.isStale) return@DataComputable true
                if (snapshotSerial < dependency.versionSerial) return@DataComputable true
            }

            false
        })
    }

    fun computeVersionSnapshot(dependencies: Iterable<SmartVersion>): VersionSnapshot {
        return groupedCompute(DataComputable {
            var dependenciesSerial = NULL_SERIAL
            var latestVersion: SmartVersion = NULL_VERSION

            for (dependency in dependencies) {
                if (dependency.isStale) dependency.nextVersion()
                if (dependenciesSerial < dependency.versionSerial) {
                    dependenciesSerial = dependency.versionSerial
                    latestVersion = dependency
                }
            }

            VersionSnapshot(currentVersion, dependenciesSerial, latestVersion)
        })
    }

    fun isMutable(dependencies: Iterable<SmartVersion>): Boolean {
        for (dependency in dependencies) {
            if (dependency.isMutable) {
                return true
            }
        }
        return false
    }

    internal fun freshenSnapshot(snapshotHolder: SnapshotHolder<VersionSnapshot>, dependencies: Iterable<SmartVersion>): Boolean {
        return freshenSnapshot(snapshotHolder, computeVersionSnapshot(dependencies))
    }

    fun <V> freshenSnapshot(snapshotHolder: DataSnapshotHolder<V>, snapshot: DataSnapshot<V>): Boolean {
        synchronized(snapshotHolder) {
            @Suppress("SENSELESS_COMPARISON")
            if (snapshotHolder.dataSnapshot == null || snapshotHolder.dataSnapshot.serial < snapshot.serial) {
                snapshotHolder.dataSnapshot = snapshot
                return true
            }
        }
        return false
    }

    internal fun <V : SerialHolder> freshenSnapshot(snapshotHolder: SnapshotHolder<V>, snapshot: V): Boolean {
        synchronized(snapshotHolder) {
            @Suppress("SENSELESS_COMPARISON")
            if (snapshotHolder.snapshot == null || snapshotHolder.snapshot.serial < snapshot.serial) {
                snapshotHolder.snapshot = snapshot
                return true
            }
        }
        return false
    }
}

const val NULL_SERIAL = Integer.MIN_VALUE
const val STALE_SERIAL = Integer.MIN_VALUE + 1
const val MIN_SERIAL = Integer.MIN_VALUE + 2
const val MAX_SERIAL = Integer.MAX_VALUE - 1
const val FRESH_SERIAL = Integer.MAX_VALUE

val NULL_VERSION = SmartImmutableVersion(NULL_SERIAL)

val NULL_VERSION_SNAPSHOT = VersionSnapshot(FRESH_SERIAL, NULL_SERIAL, NULL_VERSION)
val STALE_VERSION_SNAPSHOT = VersionSnapshot(NULL_SERIAL, STALE_SERIAL, NULL_VERSION)
val STALE_COMPUTED_VERSION_SNAPSHOT = STALE_VERSION_SNAPSHOT
val EMPTY_DEPENDENCIES = listOf<SmartVersion>()

interface SerialHolder {
    val serial: Int
}

internal interface SnapshotHolder<V : SerialHolder> {
    var snapshot: V
}

data class VersionSnapshot(val snapshotSerial: Int, val dependenciesSerial: Int, val latestVersion: SmartVersion) : SerialHolder {
    override val serial: Int = snapshotSerial
}

interface SmartVersion {
    val versionSerial: Int
    val isStale: Boolean                // return true if version needs to be refreshed
    val isMutable: Boolean              // return true if version is mutable
    val dependencies: Iterable<SmartVersion>
    fun nextVersion()                   // cause version to be updated
}

class SmartImmutableVersion(versionSerial: Int) : SmartVersion {
    constructor() : this(SmartVersionManager.nextVersion)

    final override val versionSerial: Int = versionSerial
    final override val isStale: Boolean get() = false
    final override val isMutable: Boolean get() = false
    override val dependencies: Iterable<SmartVersion> get() = EMPTY_DEPENDENCIES
    final override fun nextVersion() {
    }
}

fun SmartVersionedDataIterableAdapter(iterable: Iterable<SmartVersionedData>): Iterable<SmartVersion> = IterableAdapter(iterable, DataValueComputable<SmartVersionedData, SmartVersion> { it.version })

fun <V> IterableValueDependenciesAdapter(iterable: Iterable<SmartVersionedDataHolder<V>>): Iterable<V> = IterableAdapter(iterable, DataValueComputable<SmartVersionedDataHolder<V>, V> { it.value })

open class SmartVolatileVersion() : SmartVersion {
    protected var myVersion = SmartVersionManager.nextVersion
        private set

    override val versionSerial: Int
        get() = myVersion

    internal fun setVersion(versionSerial: Int) {
        myVersion = versionSerial
    }

    override val isStale: Boolean get() = false
    override val isMutable: Boolean get() = true
    override fun nextVersion() {
        myVersion = SmartVersionManager.nextVersion
    }

    override val dependencies: Iterable<SmartVersion> = EMPTY_DEPENDENCIES
}

abstract class SmartDependentVersionBase : SmartVersion, SnapshotHolder<VersionSnapshot> {
    protected var mySnapshot = STALE_COMPUTED_VERSION_SNAPSHOT
    protected var myMutable: Boolean? = null
    protected var myIsStale: Boolean = false

    final protected val isStaleRaw: Boolean get() = SmartVersionManager.isStale(dependencies, mySnapshot.snapshotSerial, mySnapshot.dependenciesSerial)

    override val versionSerial: Int get() = mySnapshot.dependenciesSerial
    override val isStale: Boolean get() {
        myIsStale = (myIsStale || isStaleRaw)
        return myIsStale
    }

    override val isMutable: Boolean
        get() {
            var mutable = myMutable
            if (mutable == null) {
                mutable = SmartVersionManager.isMutable(dependencies)
                myMutable = mutable
            }
            return mutable
        }

    override fun nextVersion() {
        if (isStaleRaw) {
            onNextVersion()
        }
    }

    protected open fun onInit() {
        onSuperInit()
    }

    protected open fun onSuperInit() {
        onNextVersion()
    }

    override var snapshot: VersionSnapshot
        get() = mySnapshot
        set(value) {
            mySnapshot = value
        }

    protected open fun onNextVersion() {
        myIsStale = false
        SmartVersionManager.freshenSnapshot(this, dependencies)
    }
}

open class SmartDependentVersion(dependencies: Iterable<SmartVersion>) : SmartDependentVersionBase() {
    constructor(dependency: SmartVersion) : this(listOf(dependency))

    protected val myDependencies = dependencies
    override val dependencies: Iterable<SmartVersion> get() = myDependencies

    init {
        onInit()
    }
}

open class SmartDependentRunnableVersion(dependencies: Iterable<SmartVersion>, runnable: Runnable) : SmartDependentVersion(dependencies) {
    constructor(dependency: SmartVersion, runnable: Runnable) : this(listOf(dependency), runnable)

    protected var myRunnable = runnable

    init {
        onSuperInit()
    }

    override fun onInit() {
    }

    override fun onNextVersion() {
        SmartVersionManager.groupedUpdate(Runnable {
            super.onNextVersion()
            myRunnable.run()
        })
    }
}

/**
 *  will reflect the frozen version as it was at time of instance creation with isStale reflecting
 *  whether the frozen version is out of date from its dependencies
 *
 *  isMutable is always false
 */
open class SmartCacheVersion(dependencies: Iterable<SmartVersion>) : SmartDependentVersion(dependencies) {
    constructor(dependency: SmartVersion) : this(listOf(dependency))

    init {
        onSnapshotInit()
    }

    open fun onSnapshotInit() {
        super.onNextVersion()
    }

    override fun onSuperInit() {
    }

    // we never update, we are a snapshot
    override fun onNextVersion() {
    }

    override fun nextVersion() {
    }

    override val isMutable: Boolean
        get() = false
}

/**
 *  will reflect the frozen version as it was at time of instance creation but isStale reports false
 *  effectively isolating dependents on this version from its dependencies
 *
 *  isStale is always false
 *  isMutable is always false
 */
open class SmartSnapshotVersion(dependencies: Iterable<SmartVersion>) : SmartCacheVersion(dependencies) {
    constructor(dependency: SmartVersion) : this(listOf(dependency))

    override val isStale: Boolean
        get() = false

}

open class SmartDependentVersionHolder(dependencies: Iterable<SmartVersionedData>) : SmartDependentVersion(SmartVersionedDataIterableAdapter(dependencies)) {
    constructor(dependency: SmartVersionedData) : this(listOf(dependency))
}

open class SmartDependentRunnableVersionHolder(dependencies: Iterable<SmartVersionedData>, runnable: Runnable) : SmartDependentRunnableVersion(SmartVersionedDataIterableAdapter(dependencies), runnable) {
    constructor(dependency: SmartVersionedData, runnable: Runnable) : this(listOf(dependency), runnable)
}

open class SmartCacheVersionHolder(dependencies: Iterable<SmartVersionedData>) : SmartCacheVersion(SmartVersionedDataIterableAdapter(dependencies)) {
    constructor(dependency: SmartVersionedData) : this(listOf(dependency))
}

/**
 * Versioned Data Classes
 */

data class DataSnapshot<V>(override val serial: Int, val value: V) : SerialHolder

interface DataSnapshotHolder<V> {
    var dataSnapshot: DataSnapshot<V>
}

open class SmartImmutableData<V>(name: String, value: V, versionSerial: Int) : SmartVersionedDataHolder<V> {
    constructor(name: String, value: V) : this(name, value, SmartVersionManager.nextVersion)

    constructor(value: V) : this("<unnamed>", value)

    protected val myVersion = versionSerial

    final override val versionSerial: Int get() = myVersion
    final override val isStale: Boolean get() = false
    final override val isMutable: Boolean get() = false
    override val dependencies: Iterable<SmartVersion> get() = EMPTY_DEPENDENCIES

    final override fun nextVersion() {
    }

    val myName = name

    protected val myValue = value
    override fun getValue(): V = myValue
    override var dataSnapshot: DataSnapshot<V>
        get() = DataSnapshot(myVersion, myValue)
        set(value) {
            throw UnsupportedOperationException()
        }

    override fun toString(): String {
        return "$myName:(version: $myVersion, value: $myValue)"
    }
}

open class SmartVolatileData<V>(name: String, value: V) : SmartVolatileVersion(), SmartVersionedVolatileDataHolder<V> {
    constructor(value: V) : this("<unnamed>", value)

    val myName = name
    protected var myValue: DataSnapshot<V> = DataSnapshot(myVersion, value)

    override fun getValue(): V = myValue.value

    override fun setValue(value: V) {
        if (myValue.value != value) {
            super.nextVersion()
            SmartVersionManager.freshenSnapshot(this, DataSnapshot(versionSerial, value))
        }
    }

    // used to create distributed or derived values so that the derived value has the same version from which it was derived
    internal fun setVersionedValue(value: V, versionSerial: Int) {
        myValue = DataSnapshot(versionSerial, value)
        setVersion(versionSerial)
    }

    override var dataSnapshot: DataSnapshot<V>
        get() = myValue
        set(value) {
            myValue = value
        }

    override fun toString(): String {
        return "$myName:(version: $myVersion, value: $myValue)"
    }

    override fun nextVersion() {

    }
}

open class SmartCacheData<V>(name: String, dependency: SmartVersionedDataHolder<V>) : SmartCacheVersion(dependency), SmartVersionedDataHolder<V> {
    constructor(dependency: SmartVersionedDataHolder<V>) : this("<unnamed>", dependency)

    val myName = name
    protected val myDependency = dependency
    protected val myValue = myDependency.value

    override fun onInit() {
    }

    init {
        onDataSnapshotInit()
    }

    override var dataSnapshot: DataSnapshot<V>
        get() = DataSnapshot(mySnapshot.dependenciesSerial, myValue)
        set(value) {
            throw UnsupportedOperationException()
        }

    open fun onDataSnapshotInit() {
        super.onSnapshotInit()
    }

    override fun onSnapshotInit() {
    }

    override fun getValue(): V = myValue

    override fun toString(): String {
        return "$myName:(version: ${mySnapshot.dependenciesSerial}, value: $myValue)"
    }
}

/**
 * isolating snapshot of dependent data
 *
 * isStale always false
 * isMutable always false
 */
open class SmartSnapshotData<V>(name: String, dependency: SmartVersionedDataHolder<V>) : SmartCacheData<V>(name, dependency), SmartVersionedDataHolder<V> {
    constructor(dependency: SmartVersionedDataHolder<V>) : this("<unnamed>", dependency)

    override val isStale: Boolean
        get() = false

}

open class SmartComputedData<V>(name: String, computable: DataComputable<V>) : SmartVolatileVersion(), SmartVersionedDataHolder<V> {
    constructor(name: String, computable: () -> V) : this(name, DataComputable { computable() })

    constructor(computable: DataComputable<V>) : this("<unnamed>", computable)

    constructor(computable: () -> V) : this(DataComputable { computable() })

    val myName = name
    protected var myComputable = computable
    protected var myValue = DataSnapshot(myVersion, onCompute())

    open protected fun onCompute(): V {
        return SmartVersionManager.groupedCompute(DataComputable {
            super.nextVersion()
            SmartVersionManager.freshenSnapshot(this, DataSnapshot(myVersion, myComputable.compute()))
            myValue.value
        })
    }

    override var dataSnapshot: DataSnapshot<V>
        get() = myValue
        set(value) {
            myValue = value
        }

    override fun getValue(): V {
        return myValue.value
    }

    override fun nextVersion() {
        onCompute()
    }

    override fun toString(): String {
        return "$myName:(version: $myVersion, value: $myValue)"
    }
}

open class SmartUpdateDependentData<V>(name: String, dependencies: Iterable<SmartVersionedDataHolder<*>>, computable: DataComputable<V>) :
        SmartDependentVersion(dependencies), SmartVersionedDataHolder<V> {

    constructor(dependencies: Iterable<SmartVersionedDataHolder<*>>, computable: DataComputable<V>) : this("<unnamed>", dependencies, computable)

    // plain function returning value
    constructor(name: String, dependencies: Iterable<SmartVersionedDataHolder<*>>, computable: () -> V) : this(name, dependencies, DataComputable<V> { computable() })

    constructor(dependencies: Iterable<SmartVersionedDataHolder<*>>, computable: () -> V) : this("<unnamed>", dependencies, computable)

    // single dependency versions
    constructor(name: String, dependency: SmartVersionedDataHolder<*>, computable: DataComputable<V>) : this(name, listOf(dependency), computable)

    constructor(dependency: SmartVersionedDataHolder<*>, computable: DataComputable<V>) : this(listOf(dependency), computable)

    constructor(name: String, dependency: SmartVersionedDataHolder<*>, computable: () -> V) : this(name, listOf(dependency), computable)

    constructor(dependency: SmartVersionedDataHolder<*>, computable: () -> V) : this(listOf(dependency), computable)

    val myName = name
    protected var myComputable: DataComputable<V>? = computable
    protected var myValue: DataSnapshot<V> = onCompute()

    override fun onNextVersion() {
        SmartVersionManager.groupedUpdate(Runnable {
            val computable = myComputable
            if (computable != null) {
                super.onNextVersion()
                SmartVersionManager.freshenSnapshot(this, DataSnapshot(mySnapshot.dependenciesSerial, computable.compute()))
            }
        })
    }

    open val dataDependencies: Iterable<SmartVersionedDataHolder<*>> get() = dependencies as Iterable<SmartVersionedDataHolder<*>>

    override var dataSnapshot: DataSnapshot<V>
        get() = myValue
        set(value) {
            myValue = value
        }

    open protected fun onCompute(): DataSnapshot<V> {
        onNextVersion()
        return myValue
    }

    override fun getValue(): V {
        return myValue.value
    }

    override fun toString(): String {
        return "$myName:(version: ${mySnapshot.dependenciesSerial}, value: $myValue)"
    }
}

open class SmartDependentData<V>(name: String, dependencies: Iterable<SmartVersionedDataHolder<*>>, computable: DataComputable<V>) :
        SmartUpdateDependentData<V>(name, dependencies, computable), SmartVersionedDataHolder<V> {

    constructor(dependencies: Iterable<SmartVersionedDataHolder<*>>, computable: DataComputable<V>) : this("<unnamed>", dependencies, computable)

    constructor(name: String, dependency: SmartVersionedDataHolder<*>, computable: DataComputable<V>) : this(name, listOf(dependency), computable)

    constructor(dependency: SmartVersionedDataHolder<*>, computable: DataComputable<V>) : this(listOf(dependency), computable)

    // plain function returning value
    constructor(name: String, dependencies: Iterable<SmartVersionedDataHolder<*>>, computable: () -> V) : this(name, dependencies, DataComputable<V> { computable() })

    constructor(dependencies: Iterable<SmartVersionedDataHolder<*>>, computable: () -> V) : this("<unnamed>", dependencies, computable)

    constructor(name: String, dependency: SmartVersionedDataHolder<*>, computable: () -> V) : this(name, listOf(dependency), computable)

    constructor(dependency: SmartVersionedDataHolder<*>, computable: () -> V) : this(listOf(dependency), computable)

    override fun getValue(): V {
        nextVersion()
        return myValue.value
    }

    override fun toString(): String {
        return "$myName:(version: ${mySnapshot.dependenciesSerial}, value: $myValue)"
    }
}

open class SmartUpdateIterableData<V>(name: String, dependencies: Iterable<SmartVersionedDataHolder<*>>, computable: DataValueComputable<Iterable<SmartVersionedDataHolder<*>>, V>) :
        SmartDependentVersion(dependencies), SmartVersionedDataHolder<V> {

    constructor(dependencies: Iterable<SmartVersionedDataHolder<*>>, computable: DataValueComputable<Iterable<SmartVersionedDataHolder<*>>, V>) : this("<unnamed>", dependencies, computable)

    // function taking iterable
    constructor(name: String, dependencies: Iterable<SmartVersionedDataHolder<*>>, computable: (Iterable<SmartVersionedDataHolder<*>>) -> V) : this(name, dependencies, DataValueComputable<Iterable<SmartVersionedDataHolder<*>>, V> { computable(it) })

    constructor(dependencies: Iterable<SmartVersionedDataHolder<*>>, computable: (Iterable<SmartVersionedDataHolder<*>>) -> V) : this("<unnamed>", dependencies, computable)

    // single dependency versions
    constructor(name: String, dependency: SmartVersionedDataHolder<*>, computable: DataValueComputable<Iterable<SmartVersionedDataHolder<*>>, V>) : this(name, listOf(dependency), computable)

    constructor(dependency: SmartVersionedDataHolder<*>, computable: DataValueComputable<Iterable<SmartVersionedDataHolder<*>>, V>) : this(listOf(dependency), computable)

    constructor(name: String, dependency: SmartVersionedDataHolder<V>) : this(name, listOf(dependency), DataValueComputable<Iterable<SmartVersionedDataHolder<*>>, V> { dependency.value })

    constructor(dependency: SmartVersionedDataHolder<V>) : this("<unnamed>", dependency)

    val myName = name
    protected var myComputable: DataValueComputable<Iterable<SmartVersionedDataHolder<*>>, V>? = computable
    protected var myValue: DataSnapshot<V> = onCompute()

    override fun onNextVersion() {
        SmartVersionManager.groupedUpdate(Runnable {
            val computable = myComputable
            if (computable != null) {
                super.onNextVersion()
                SmartVersionManager.freshenSnapshot(this, DataSnapshot(mySnapshot.dependenciesSerial, computable.compute(dataDependencies)))
            }
        })
    }

    open val dataDependencies: Iterable<SmartVersionedDataHolder<*>> get() = dependencies as Iterable<SmartVersionedDataHolder<*>>

    override var dataSnapshot: DataSnapshot<V>
        get() = myValue
        set(value) {
            myValue = value
        }

    open protected fun onCompute(): DataSnapshot<V> {
        onNextVersion()
        return myValue
    }

    override fun getValue(): V {
        return myValue.value
    }

    override fun toString(): String {
        return "$myName:(version: ${mySnapshot.dependenciesSerial}, value: $myValue)"
    }
}

open class SmartIterableData<V>(name: String, dependencies: Iterable<SmartVersionedDataHolder<*>>, computable: DataValueComputable<Iterable<SmartVersionedDataHolder<*>>, V>) :
        SmartUpdateIterableData<V>(name, dependencies, computable), SmartVersionedDataHolder<V> {

    constructor(dependencies: Iterable<SmartVersionedDataHolder<*>>, computable: DataValueComputable<Iterable<SmartVersionedDataHolder<*>>, V>) : this("<unnamed>", dependencies, computable)

    // function taking iterable
    constructor(name: String, dependencies: Iterable<SmartVersionedDataHolder<*>>, computable: (Iterable<SmartVersionedDataHolder<*>>) -> V) : this(name, dependencies, DataValueComputable<Iterable<SmartVersionedDataHolder<*>>, V> { computable(it) })

    constructor(dependencies: Iterable<SmartVersionedDataHolder<*>>, computable: (Iterable<SmartVersionedDataHolder<*>>) -> V) : this("<unnamed>", dependencies, computable)

    // data computable
    constructor(name: String, dependencies: Iterable<SmartVersionedDataHolder<*>>, computable: DataComputable<V>) : this(name, dependencies, DataValueComputable<Iterable<SmartVersionedDataHolder<*>>, V> { computable.compute() })

    constructor(dependencies: Iterable<SmartVersionedDataHolder<*>>, computable: DataComputable<V>) : this("<unnamed>", dependencies, computable)

    // plain function returning value
    constructor(name: String, dependencies: Iterable<SmartVersionedDataHolder<*>>, computable: () -> V) : this(name, dependencies, DataValueComputable<Iterable<SmartVersionedDataHolder<*>>, V> { computable() })

    constructor(dependencies: Iterable<SmartVersionedDataHolder<*>>, computable: () -> V) : this("<unnamed>", dependencies, computable)


    // single dependency versions
    constructor(name: String, dependency: SmartVersionedDataHolder<*>, computable: DataValueComputable<Iterable<SmartVersionedDataHolder<*>>, V>) : this(name, listOf(dependency), computable)

    constructor(dependency: SmartVersionedDataHolder<*>, computable: DataValueComputable<Iterable<SmartVersionedDataHolder<*>>, V>) : this(listOf(dependency), computable)

    constructor(name: String, dependency: SmartVersionedDataHolder<*>, computable: DataComputable<V>) : this(name, listOf(dependency), computable)

    constructor(dependency: SmartVersionedDataHolder<*>, computable: DataComputable<V>) : this(listOf(dependency), computable)

    constructor(name: String, dependency: SmartVersionedDataHolder<V>) : this(name, listOf(dependency), DataValueComputable<Iterable<SmartVersionedDataHolder<*>>, V> { dependency.value })

    constructor(dependency: SmartVersionedDataHolder<V>) : this("<unnamed>", dependency)

    constructor(name: String, dependency: SmartVersionedDataHolder<*>, computable: () -> V) : this(name, listOf(dependency), computable)

    constructor(dependency: SmartVersionedDataHolder<*>, computable: () -> V) : this(listOf(dependency), computable)

    override fun getValue(): V {
        nextVersion()
        return myValue.value
    }

    override fun toString(): String {
        return "$myName:(version: ${mySnapshot.dependenciesSerial}, value: $myValue)"
    }
}

open class SmartUpdateVectorData<V>(name: String, dependencies: Iterable<SmartVersionedDataHolder<V>>, computable: IterableDataComputable<V>) :
        SmartDependentVersion(dependencies),
        SmartVersionedDataHolder<V> {
    constructor(dependencies: Iterable<SmartVersionedDataHolder<V>>, computable: IterableDataComputable<V>) : this("<unnamed>", dependencies, computable)

    constructor(name: String, dependencies: Iterable<SmartVersionedDataHolder<V>>, computable: (Iterable<V>) -> V) : this(name, dependencies, IterableDataComputable { computable(it) })

    constructor(dependencies: Iterable<SmartVersionedDataHolder<V>>, computable: (Iterable<V>) -> V) : this(dependencies, IterableDataComputable { computable(it) })

    constructor(name: String, dependency: SmartVersionedDataHolder<V>, computable: IterableDataComputable<V>) : this(name, listOf(dependency), computable)

    constructor(dependency: SmartVersionedDataHolder<V>, computable: IterableDataComputable<V>) : this(listOf(dependency), computable)

    constructor(name: String, dependency: SmartVersionedDataHolder<V>, computable: (Iterable<V>) -> V) : this(name, listOf(dependency), computable)

    constructor(dependency: SmartVersionedDataHolder<V>, computable: (Iterable<V>) -> V) : this(listOf(dependency), computable)

    val myName = name
    protected var myComputable: IterableDataComputable<V>? = computable
    protected var myValue = onCompute()

    override fun onNextVersion() {
        SmartVersionManager.groupedUpdate(Runnable {
            val computable = myComputable
            if (computable != null) {
                super.onNextVersion()
                SmartVersionManager.freshenSnapshot(this, DataSnapshot(mySnapshot.dependenciesSerial, computable.compute(valueDependencies)))
            }
        })
    }

    val valueDependencies: Iterable<V>
        get() = IterableValueDependenciesAdapter(super.dependencies as Iterable<SmartVersionedDataHolder<V>>)

    open protected fun onCompute(): DataSnapshot<V> {
        onNextVersion()
        return myValue
    }

    override var dataSnapshot: DataSnapshot<V>
        get() = myValue
        set(value) {
            myValue = value
        }

    override fun getValue(): V {
        return myValue.value
    }

    override fun toString(): String {
        return "$myName:(version: ${mySnapshot.dependenciesSerial}, value: $myValue)"
    }
}

open class SmartVectorData<V>(name: String, dependencies: Iterable<SmartVersionedDataHolder<V>>, computable: IterableDataComputable<V>) :
        SmartUpdateVectorData<V>(name, dependencies, computable), SmartVersionedDataHolder<V> {
    constructor(dependencies: Iterable<SmartVersionedDataHolder<V>>, computable: IterableDataComputable<V>) : this("<unnamed>", dependencies, computable)

    constructor(name: String, dependencies: Iterable<SmartVersionedDataHolder<V>>, computable: (Iterable<V>) -> V) : this(name, dependencies, IterableDataComputable { computable(it) })

    constructor(dependencies: Iterable<SmartVersionedDataHolder<V>>, computable: (Iterable<V>) -> V) : this(dependencies, IterableDataComputable { computable(it) })

    constructor(name: String, dependency: SmartVersionedDataHolder<V>, computable: IterableDataComputable<V>) : this(name, listOf(dependency), computable)

    constructor(dependency: SmartVersionedDataHolder<V>, computable: IterableDataComputable<V>) : this(listOf(dependency), computable)

    constructor(name: String, dependency: SmartVersionedDataHolder<V>, computable: (Iterable<V>) -> V) : this(name, listOf(dependency), computable)

    constructor(dependency: SmartVersionedDataHolder<V>, computable: (Iterable<V>) -> V) : this(listOf(dependency), computable)

    override fun getValue(): V {
        nextVersion()
        return myValue.value
    }
}

//open class LatestIterableDataComputable<V>(val dependencies: Iterable<SmartVersionedDataHolder<V>>) : DataComputable<DataSnapshot<V>> {
//    override fun compute(): DataSnapshot<V> {
//        val iterator = dependencies.iterator()
//        var latestDataSnapshot: DataSnapshot<V> = iterator.next().dataSnapshot
//        for (data in iterator) {
//            if (latestDataSnapshot.serial < data.dataSnapshot.serial) {
//                latestDataSnapshot = data.dataSnapshot
//            }
//        }
//        return latestDataSnapshot
//    }
//}

open class SmartLatestDependentData<V>(name: String, dependencies: Iterable<SmartVersionedDataHolder<V>>, runnable: Runnable?) : SmartDependentVersion(dependencies), SmartVersionedDataHolder<V> {
    constructor(name: String, dependencies: Iterable<SmartVersionedDataHolder<V>>) : this(name, dependencies, null)

    constructor(dependencies: Iterable<SmartVersionedDataHolder<V>>) : this("<unnamed>", dependencies, null)

    constructor(dependencies: Iterable<SmartVersionedDataHolder<V>>, runnable: Runnable) : this("<unnamed>", dependencies, runnable)

    constructor(name: String, dependencies: Iterable<SmartVersionedDataHolder<V>>, runnable: () -> Unit) : this(name, dependencies, Runnable { runnable() })

    constructor(dependencies: Iterable<SmartVersionedDataHolder<V>>, runnable: () -> Unit) : this("<unnamed>", dependencies, Runnable { runnable() })

    init {
        if (dependencies.none()) throw IllegalArgumentException("dependencies for latest dependent data cannot be empty")
    }

    val myName = name
    protected val myRunnable = runnable
    protected val myComputable = DataComputable<DataSnapshot<V>> {
        DataSnapshot(mySnapshot.snapshotSerial, (mySnapshot.latestVersion as SmartVersionedDataHolder<V>).dataSnapshot.value)
    }

    protected var myValue = onCompute()

    override fun onNextVersion() {
        SmartVersionManager.groupedUpdate(Runnable {
            val computable = myComputable
            @Suppress("SENSELESS_COMPARISON")
            if (computable != null) {
                super.onNextVersion()
                val runnable = myRunnable
                if (SmartVersionManager.freshenSnapshot(this, computable.compute()) && runnable != null) {
                    runnable.run()
                }
            }
        })
    }

    open protected fun onCompute(): DataSnapshot<V> {
        onNextVersion()
        return myValue
    }

    override var dataSnapshot: DataSnapshot<V>
        get() = myValue
        set(value) {
            myValue = value
        }

    override fun getValue(): V {
        nextVersion()
        return myValue.value
    }

    override fun toString(): String {
        return "$myName:(version: ${mySnapshot.dependenciesSerial}, value: $myValue)"
    }
}

open class SmartVersionedDataAlias<V>(name: String, aliased: SmartVersionedDataHolder<V>) : SmartVersionedDataHolder<V>, SmartVersionedVolatileDataHolder<V> {
    constructor(aliased: SmartVersionedDataHolder<V>) : this("<unnamed>", aliased)

    protected val myName = name
    protected var myVersion: Int = SmartVersionManager.nextVersion

    // unwrap nested alias references
    protected var myAliased = if (aliased is SmartVersionedDataAlias<V>) aliased.alias else aliased
    protected var myRecursion = RecursionGuard()
    protected var myLastDataSnapshot: DataSnapshot<V> = myAliased.dataSnapshot

    protected fun updateDataSnapshot() {
        myLastDataSnapshot = myAliased.dataSnapshot
    }

    var alias: SmartVersionedDataHolder<V>
        get() = myAliased
        set(value) {
            myAliased = value
            touchVersionSerial()
            updateDataSnapshot()
        }

    fun touchVersionSerial() {
        myVersion = SmartVersionManager.nextVersion
    }

    override val versionSerial: Int
        get() = myRecursion.guarded(
                { myVersion.max(myLastDataSnapshot.serial) },
                {
                    val serial = myAliased.versionSerial
                    updateDataSnapshot()
                    myVersion.max(serial)
                })

    override val isStale: Boolean
        get() = myRecursion.guarded(
                { false },
                {
                    val stale = myAliased.isStale
                    updateDataSnapshot()
                    stale
                })

    override val isMutable: Boolean
        get() = true

    override val dependencies: Iterable<SmartVersion>
        get() = myAliased.dependencies

    override fun nextVersion() {
        myRecursion.guarded() {
            myAliased.nextVersion()
            updateDataSnapshot()
        }
    }

    override var dataSnapshot: DataSnapshot<V>
        get() {
            updateDataSnapshot()
            return myLastDataSnapshot
        }
        set(value) {
            throw UnsupportedOperationException()
        }

    override fun getValue(): V = myRecursion.guarded(
            { myLastDataSnapshot.value },
            {
                updateDataSnapshot()
                myAliased.value
            })

    override fun setValue(value: V) {
        val versionedDataHolder = myAliased
        if (versionedDataHolder is SmartVersionedVolatileDataHolder<V>) {
            versionedDataHolder.value = value
            updateDataSnapshot()
        } else {
            throw UnsupportedOperationException()
        }
    }

    override fun toString(): String {
        return "$myName: (version: $myVersion, alias: $myAliased)"
    }
}

/**
 *  This behaves like volatile data but has an optional data point connection that will allow its value to be changed by a dependency
 *
 *  The final value is the latest value between the volatile version and the external dependency version, effectively just like volatile data will hold the last value written
 *
 */
open class SmartVersionedProperty<V>(name: String, initialValue: V, runnable: ValueRunnable<V>?) : SmartDependentVersionBase(), SmartVersionedPropertyHolder<V> {
    constructor(name: String, initialValue: V, runnable: (V) -> Unit) : this(name, initialValue, ValueRunnable { runnable(it) })

    constructor(initialValue: V, runnable: (V) -> Unit) : this(initialValue, ValueRunnable { runnable(it) })

    constructor(initialValue: V, runnable: ValueRunnable<V>?) : this("<unnamed>", initialValue, runnable)

    constructor(initialValue: V) : this("<unnamed>", initialValue, null)

    protected val myName = name
    protected val myRunnable = runnable
    internal val myValue = SmartVolatileData<V>("$myName.value", initialValue)
    protected var myExternal: SmartVersionedDataHolder<V> = SmartImmutableData("", initialValue, NULL_SERIAL)
    protected var myDependenciesVersion = SmartImmutableVersion()

    protected var myDataSnapshot: DataSnapshot<V> = myValue.dataSnapshot
    protected var myDependencies: Iterable<SmartVersion> = listOf(myValue)

    override val dependencies: Iterable<SmartVersion> get() = myDependencies

    init {
        onInit()
    }

    override fun connect(aliased: SmartVersionedDataHolder<V>) {
        myExternal = aliased
        myDependencies = listOf(myValue, myExternal, myDependenciesVersion)
        nextDependencies()
    }

    override fun disconnect() {
        myExternal = SmartImmutableData("", myDataSnapshot.value, NULL_SERIAL)
        myDependencies = listOf(myValue, myDependenciesVersion)
        nextDependencies()
    }

    override fun connectionFinalized() {
        val external = myExternal
        if (external is SmartVersionedDataAlias<V>) {
            connect(external.alias)
        }
    }

    private fun nextDependencies() {
        myDependenciesVersion = SmartImmutableVersion()
        myDataSnapshot = DataSnapshot(STALE_SERIAL, myDataSnapshot.value)
        mySnapshot = STALE_VERSION_SNAPSHOT
    }

    open fun onCompute(): DataSnapshot<V> {
        if (myExternal.isStale) myExternal.nextVersion()
        return if (myValue.versionSerial >= myExternal.versionSerial) myValue.dataSnapshot
        else myExternal.dataSnapshot
    }

    // used by property array during distribution/aggregation to set version to the version of the property from which it is derived
    internal fun setVersionedValue(value: V, versionSerial: Int) {
        myValue.setVersionedValue(value, versionSerial)
        mySnapshot = VersionSnapshot(versionSerial, versionSerial, NULL_VERSION)
        myDataSnapshot = DataSnapshot(versionSerial, value)
    }

    override fun onNextVersion() {
        SmartVersionManager.groupedUpdate {
            super.onNextVersion()
            val runnable = myRunnable
            if (SmartVersionManager.freshenSnapshot(this, onCompute()) && runnable != null) {
                runnable.run(myDataSnapshot.value)
            }
        }
    }

    override val isMutable: Boolean
        get() = true

    override var dataSnapshot: DataSnapshot<V>
        get() = myDataSnapshot
        set(value) {
            myDataSnapshot = value
        }

    override fun getValue(): V {
        nextVersion()
        return myDataSnapshot.value
    }

    override fun setValue(value: V) {
        myValue.value = value
    }

    override fun toString(): String {
        return if (myExternal is SmartImmutableData) "$myName: ($myValue, external: unconnected)"
        else "$myName: ($myValue, external: $myExternal)"
    }
}

class MutableIteratorAdapter<V>(val iterator: Iterator<V>) : MutableIterator<V> {
    override fun hasNext(): Boolean {
        return iterator.hasNext()
    }

    override fun next(): V {
        return iterator.next()
    }

    override fun remove() {
        throw UnsupportedOperationException()
    }
}

open class SmartVersionedPropertyArray<V>(name: String, size: Int, initialValue: V, aggregate: DataValueComputable<Iterable<V>, V>, distribute: DataValueComputable<V, Iterator<V>>) : SmartDependentVersionBase(), SmartVersionedPropertyArrayHolder<V> {

    protected val myName = name
    protected val myAggregateComputable = aggregate
    protected val myDistributeComputable = distribute
    protected val myProperty: SmartVersionedProperty<V>
    protected val myProperties: List<SmartVersionedProperty<V>>
//    protected var myRecursion = RecursionGuard()

    // distribute the given value, versionSerial of distributed properties will be the same as the versionSerial of the current snapshot
    // that way the distributed version is the same as the version being distributed
    protected fun onDistribute(value: V) {
//        myRecursion.guarded() {
            val iterator = myDistributeComputable.compute(value)
            for (i in 1..myProperties.lastIndex) {
                if (!iterator.hasNext()) break
                myProperties[i].setVersionedValue(iterator.next(), myProperty.versionSerial)
            }
//        }
    }

    // aggregate the array values, versionSerial of aggregated property will be the same as the versionSerial of the current snapshot
    // that way the aggregated version is the same as the version of the dependencies being aggregated
    protected fun onAggregate() {
//        myRecursion.guarded() {
            val aggregated = myAggregateComputable.compute(IterableValueDependenciesAdapter(myProperties.subList(1, myProperties.size)))
            myProperty.setVersionedValue(aggregated, mySnapshot.dependenciesSerial)
//        }
    }

    init {
        val properties = ArrayList<SmartVersionedProperty<V>>()
        val distributed = myDistributeComputable.compute(initialValue)

        myProperty = SmartVersionedProperty("$myName.value", initialValue, ValueRunnable<V> { onDistribute(it) })
        properties.add(myProperty)

        val aggregateRunnable = ValueRunnable<V> { onAggregate() }
        for (i in 1..size) {
            val value = distributed.next()
            properties.add(SmartVersionedProperty("$myName[$i]", value, aggregateRunnable))
        }
        myProperties = properties
    }

    override val dependencies: Iterable<SmartVersion>
        get() = myProperties

    override fun connect(aliased: SmartVersionedDataHolder<V>) {
        myProperty.connect(aliased)
    }

    override fun disconnect() {
        myProperty.disconnect()
    }

    override fun connectionFinalized() {
        myProperty.connectionFinalized()
    }

    override val isMutable: Boolean
        get() = true

    override var dataSnapshot: DataSnapshot<V>
        get() = myProperty.dataSnapshot
        set(value) {
            myProperty.dataSnapshot = value
        }

    override fun getValue(): V {
        nextVersion()
        return myProperty.value
    }

    override fun setValue(value: V) {
        myProperty.value = value
    }

    override fun get(index: Int): SmartVersionedPropertyHolder<V> {
        return myProperties[index + 1]
    }

    override fun iterator(): MutableIterator<SmartVersionedPropertyHolder<V>> {
        return MutableIteratorAdapter(myProperties.subList(1, myProperties.size).iterator())
    }

    override fun toString(): String {
        val sb = StringBuilder()
        sb += myName + "[${myProperties.size}] ("
        var first = true
        for (prop in myProperties) {
            if (!first) sb += ", "
            else first = false

            sb += prop
        }
        sb += ")"
        return sb.toString()
    }
}

