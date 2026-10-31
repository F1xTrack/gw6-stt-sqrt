package dev.f1xtrack.gw6voice.phone;
import androidx.room.*;
import java.util.List;
@Dao public interface JobDao {
    @Query("SELECT * FROM jobs ORDER BY createdAt DESC") List<JobRow> all();
    @Query("SELECT * FROM jobs WHERE id = :id LIMIT 1") JobRow get(String id);
    @Insert(onConflict = OnConflictStrategy.IGNORE) long insert(JobRow row);
    @Update void update(JobRow row);
    @Query("DELETE FROM jobs WHERE conversationId = :id") void deleteConversation(String id);
    @Query("DELETE FROM jobs WHERE id = :id") void delete(String id);
}
