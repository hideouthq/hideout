package gg.hideouthq.core.data.fake

import gg.hideouthq.core.data.MigrationRecords
import gg.hideouthq.core.data.RecordWrite
import gg.hideouthq.core.library.CitadelPath
import gg.hideouthq.core.library.MigrationId

class InMemoryMigrationRecords : MigrationRecords {
    val records: MutableMap<MigrationId, ByteArray> = linkedMapOf()

    val copies: MutableMap<MigrationId, Map<CitadelPath, ByteArray>> = linkedMapOf()

    override suspend fun begin(id: MigrationId, record: ByteArray, copies: Map<CitadelPath, ByteArray>): RecordWrite {
        if (id in records) return RecordWrite.Failed("a record ${id.value} exists already")
        this.copies[id] = copies.mapValues { it.value.copyOf() }
        records[id] = record.copyOf()
        return RecordWrite.Written
    }

    override suspend fun update(id: MigrationId, record: ByteArray): RecordWrite {
        if (id !in records) return RecordWrite.Failed("there is no record ${id.value}")
        records[id] = record.copyOf()
        return RecordWrite.Written
    }

    override suspend fun all(): Map<MigrationId, ByteArray> = records.toMap()
}
