package dev.f1xtrack.gw6voice.phone;
import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;
@Entity(tableName = "jobs")
public class JobRow {
    @PrimaryKey @NonNull public String id = "";
    @NonNull public String conversationId = "";
    public long createdAt;
    @NonNull public String payload = "";
}
