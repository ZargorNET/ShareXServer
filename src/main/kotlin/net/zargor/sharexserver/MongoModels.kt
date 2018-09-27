package net.zargor.sharexserver

import com.google.gson.annotations.SerializedName
import org.bson.BsonBinary
import java.util.*

data class HeaderDoc(
        @field:SerializedName("_id") val id : String,
        @field:SerializedName("delete_key") val deleteKey : String,
        @field:SerializedName("content_type") val contentType : String,
        @field:SerializedName("content_length") val contentLength : Int,
        @field:SerializedName("uploaded_at") val uploadedAt : Long,
        @field:SerializedName("total_chunks") val totalChunks : Int
);

data class ChunkDoc(
        @field:SerializedName("parent_id") val parentId : String,
        val index : Int,
        val data : ByteArray
) {
    override fun equals(other : Any?) : Boolean {
        if (this === other) return true
        if (other !is ChunkDoc) return false

        if (parentId != other.parentId) return false
        if (index != other.index) return false
        if (!Arrays.equals(data, other.data)) return false

        return true
    }

    override fun hashCode() : Int {
        var result = parentId.hashCode()
        result = 31 * result + index
        result = 31 * result + Arrays.hashCode(data)
        return result
    }
}