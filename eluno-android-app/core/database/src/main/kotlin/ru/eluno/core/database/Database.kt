package ru.eluno.core.database

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.Upsert
import androidx.room3.Transaction
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ru.eluno.core.domain.AppSettings
import ru.eluno.core.domain.DeviceIcon
import ru.eluno.core.domain.SettingsRepository
import ru.eluno.core.domain.ThemeMode

@Entity(tableName = "devices")
data class DeviceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val category: String,
    val nodeIdHex: String,
    val endpointId: Int,
    val capabilities: String,
    val icon: String = DeviceIcon.Automatic.name,
)

@Entity(tableName = "snapshots")
data class SnapshotEntity(
    @PrimaryKey val deviceId: String,
    val isOn: Boolean?,
    val brightness: Int?,
    val colorTemperature: Int?,
    val colorRgb: Int?,
    val freshness: String,
    val availability: String,
    val lastConfirmedEpochMs: Long?,
    val pendingId: String?,
    val pendingType: String?,
    val pendingValue: String?,
    val pendingAtEpochMs: Long?,
    val errorType: String?,
    val errorMessage: String?,
)

@Entity(tableName = "command_records")
data class CommandEntity(
    @PrimaryKey val id: String,
    val deviceId: String,
    val type: String,
    val value: String,
    val status: String,
    val createdAtEpochMs: Long,
    val error: String?,
)

@Entity(tableName = "widget_bindings")
data class WidgetBindingEntity(
    @PrimaryKey val appWidgetId: Int,
    val deviceId: String,
    @ColumnInfo(defaultValue = "1") val backgroundEnabled: Boolean = true,
    @ColumnInfo(defaultValue = "100") val backgroundOpacity: Int = 100,
    @ColumnInfo(defaultValue = "'System'") val colorMode: String = "System",
)

@Dao
interface ElunoDao {
    @Transaction suspend fun saveCommissionedDevices(devices: List<DeviceEntity>) {
        devices.forEach { if (getDevice(it.id) == null) upsertDevice(it) }
    }
    @Transaction suspend fun saveCommandState(command: CommandEntity, snapshot: SnapshotEntity) {
        upsertCommand(command)
        upsertSnapshot(snapshot)
    }
    @Query("SELECT * FROM devices ORDER BY name COLLATE NOCASE") fun observeDevices(): Flow<List<DeviceEntity>>
    @Query("SELECT * FROM devices WHERE id = :id") fun observeDevice(id: String): Flow<DeviceEntity?>
    @Query("SELECT * FROM devices WHERE id = :id") suspend fun getDevice(id: String): DeviceEntity?
    @Query("SELECT * FROM snapshots WHERE deviceId = :id") fun observeSnapshot(id: String): Flow<SnapshotEntity?>
    @Query("SELECT * FROM snapshots WHERE deviceId = :id") suspend fun getSnapshot(id: String): SnapshotEntity?
    @Query("SELECT * FROM widget_bindings WHERE appWidgetId = :id") fun observeWidgetBinding(id: Int): Flow<WidgetBindingEntity?>
    @Query("SELECT * FROM widget_bindings WHERE appWidgetId = :id") suspend fun getWidgetBinding(id: Int): WidgetBindingEntity?
    @Upsert suspend fun upsertWidgetBinding(binding: WidgetBindingEntity)
    @Query("DELETE FROM widget_bindings WHERE appWidgetId = :id") suspend fun deleteWidgetBinding(id: Int)
    @Upsert suspend fun upsertDevice(entity: DeviceEntity)
    @Upsert suspend fun upsertSnapshot(entity: SnapshotEntity)
    @Upsert suspend fun upsertCommand(entity: CommandEntity)
    @Query("UPDATE devices SET name = :name WHERE id = :id") suspend fun rename(id: String, name: String): Int
    @Query("UPDATE devices SET icon = :icon WHERE id = :id") suspend fun setIcon(id: String, icon: String): Int
}

@Database(
    entities = [DeviceEntity::class, SnapshotEntity::class, CommandEntity::class, WidgetBindingEntity::class],
    version = 7,
    exportSchema = true,
)
abstract class ElunoDatabase : RoomDatabase() {
    abstract fun dao(): ElunoDao
    companion object {
        fun create(context: Context): ElunoDatabase = Room.databaseBuilder<ElunoDatabase>(
            context.applicationContext,
            "eluno.db",
        ).addMigrations(object : Migration(1, 2) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execSQL("CREATE TABLE IF NOT EXISTS commissioning (nodeIdHex TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, status TEXT NOT NULL)")
            }
        }, object : Migration(2, 3) {
            override suspend fun migrate(connection: SQLiteConnection) {
                // Only legacy synthetic IDs/template bindings are removed; real Matter data stays.
                connection.execSQL("DELETE FROM command_records WHERE deviceId GLOB 'demo-*' OR deviceId IN (SELECT id FROM devices WHERE templateId IS NOT NULL)")
                connection.execSQL("DELETE FROM snapshots WHERE deviceId GLOB 'demo-*' OR deviceId IN (SELECT id FROM devices WHERE templateId IS NOT NULL)")
                connection.execSQL("DELETE FROM widget_bindings WHERE deviceId GLOB 'demo-*' OR deviceId IN (SELECT id FROM devices WHERE templateId IS NOT NULL)")
                connection.execSQL("CREATE TABLE devices_new (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, category TEXT NOT NULL, nodeIdHex TEXT NOT NULL, endpointId INTEGER NOT NULL, capabilities TEXT NOT NULL)")
                connection.execSQL("INSERT INTO devices_new (id, name, category, nodeIdHex, endpointId, capabilities) SELECT id, name, category, nodeIdHex, endpointId, capabilities FROM devices WHERE templateId IS NULL AND id NOT GLOB 'demo-*'")
                connection.execSQL("DROP TABLE devices")
                connection.execSQL("ALTER TABLE devices_new RENAME TO devices")
            }
        }, object : Migration(3, 4) {
            override suspend fun migrate(connection: SQLiteConnection) {
                // Only the obsolete attempt journal is removed; commissioned devices and fabric keys stay.
                connection.execSQL("DROP TABLE IF EXISTS commissioning")
            }
        }, object : Migration(4, 5) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE devices ADD COLUMN icon TEXT NOT NULL DEFAULT 'Automatic'")
            }
        }, object : Migration(5, 6) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE widget_bindings ADD COLUMN backgroundEnabled INTEGER NOT NULL DEFAULT 1")
                connection.execSQL("ALTER TABLE widget_bindings ADD COLUMN backgroundOpacity INTEGER NOT NULL DEFAULT 100")
            }
        }, object : Migration(6, 7) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE widget_bindings ADD COLUMN colorMode TEXT NOT NULL DEFAULT 'System'")
            }
        }).build()
    }
}

fun createElunoDao(context: Context): ElunoDao = ElunoDatabase.create(context).dao()

private val Context.settingsDataStore by preferencesDataStore("eluno_settings")

class DataStoreSettingsRepository(private val context: Context) : SettingsRepository {
    private val themeKey = stringPreferencesKey("theme")
    private val dynamicKey = booleanPreferencesKey("dynamic_colors")
    override val settings: Flow<AppSettings> = context.settingsDataStore.data.map { preferences ->
        AppSettings(
            themeMode = preferences[themeKey]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.System,
            dynamicColors = preferences[dynamicKey] ?: true,
        )
    }
    override suspend fun setTheme(mode: ThemeMode) { context.settingsDataStore.edit { it[themeKey] = mode.name } }
    override suspend fun setDynamicColors(enabled: Boolean) { context.settingsDataStore.edit { it[dynamicKey] = enabled } }
}
