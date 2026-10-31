package dev.f1xtrack.gw6voice.phone;
import androidx.room.*;
@Database(entities = {JobRow.class}, version = 1, exportSchema = false)
public abstract class JobDatabase extends RoomDatabase { public abstract JobDao jobs(); }
