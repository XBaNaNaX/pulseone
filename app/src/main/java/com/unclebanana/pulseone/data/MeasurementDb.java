package com.unclebanana.pulseone.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Private, app-scoped health trend storage. No network or shared storage access. */
public final class MeasurementDb extends SQLiteOpenHelper {
    private static final String DB_NAME = "pulse_one.db";
    private static final int DB_VERSION = 1;

    public MeasurementDb(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
        setWriteAheadLoggingEnabled(true);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE measurements ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "recorded_at INTEGER NOT NULL,"
                + "type TEXT NOT NULL,"
                + "value REAL NOT NULL,"
                + "unit TEXT NOT NULL,"
                + "source TEXT NOT NULL DEFAULT 'bluetooth_standard')");
        db.execSQL("CREATE INDEX idx_measurements_type_time "
                + "ON measurements(type, recorded_at DESC)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // First schema version. Future migrations must preserve health history.
    }

    public void insert(long timestampMillis, String type, double value, String unit) {
        ContentValues row = new ContentValues();
        row.put("recorded_at", timestampMillis);
        row.put("type", type);
        row.put("value", value);
        row.put("unit", unit);
        getWritableDatabase().insertOrThrow("measurements", null, row);
    }

    public List<Float> recentValues(String type, int limit) {
        List<Float> values = new ArrayList<>();
        String boundedLimit = Integer.toString(Math.max(1, Math.min(limit, 1000)));
        try (Cursor cursor = getReadableDatabase().query(
                "measurements", new String[]{"value"}, "type = ?",
                new String[]{type}, null, null, "recorded_at DESC", boundedLimit)) {
            while (cursor.moveToNext()) values.add(0, cursor.getFloat(0));
        }
        return values;
    }

    public long count() {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM measurements", null)) {
            return cursor.moveToFirst() ? cursor.getLong(0) : 0;
        }
    }

    public void exportCsv(OutputStream output) throws IOException {
        output.write("recorded_at_utc,type,value,unit,source\n".getBytes(StandardCharsets.UTF_8));
        try (Cursor cursor = getReadableDatabase().query(
                "measurements",
                new String[]{"recorded_at", "type", "value", "unit", "source"},
                null, null, null, null, "recorded_at ASC")) {
            while (cursor.moveToNext()) {
                String line = csv(Instant.ofEpochMilli(cursor.getLong(0)).toString()) + ','
                        + csv(cursor.getString(1)) + ','
                        + String.format(Locale.US, "%.4f", cursor.getDouble(2)) + ','
                        + csv(cursor.getString(3)) + ','
                        + csv(cursor.getString(4)) + '\n';
                output.write(line.getBytes(StandardCharsets.UTF_8));
            }
        }
        output.flush();
    }

    private static String csv(String value) {
        if (value == null) return "";
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
