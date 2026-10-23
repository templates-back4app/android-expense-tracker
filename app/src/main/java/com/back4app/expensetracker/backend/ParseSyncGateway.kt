package com.back4app.expensetracker.backend

import com.back4app.expensetracker.sync.PendingOp
import com.back4app.expensetracker.sync.PullPage
import com.back4app.expensetracker.sync.ResponseParser
import com.back4app.expensetracker.sync.SyncGateway
import com.back4app.expensetracker.sync.SyncResponse
import com.parse.ParseCloud
import com.parse.ParseException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

/** Calls the two Cloud functions with the Parse Android SDK (session token of the signed-in user is attached). */
class ParseSyncGateway : SyncGateway {

    override suspend fun syncOp(op: PendingOp): SyncResponse = withContext(Dispatchers.IO) {
        val params = HashMap<String, Any>()
        params["opId"] = op.opId
        params["entryId"] = op.entryKey
        params["op"] = op.op.wire
        op.baseVersion?.let { params["baseVersion"] = it }
        val fields = HashMap<String, Any>()
        for ((k, v) in op.fields) if (v != null) fields[k] = v
        params["fields"] = fields
        ResponseParser.syncResponse(call("syncOp", params))
    }

    override suspend fun listEntries(since: Long?): PullPage = withContext(Dispatchers.IO) {
        val params = HashMap<String, Any>()
        if (since != null) params["since"] = Instant.ofEpochMilli(since).toString()
        params["limit"] = PAGE_SIZE
        ResponseParser.pullPage(call("listEntries", params))
    }

    private fun call(name: String, params: HashMap<String, Any>): Map<*, *> = try {
        val result: Any? = ParseCloud.callFunction<Any?>(name, params)
        result as? Map<*, *> ?: throw SyncGateway.Failure(null, "$name returned ${result?.javaClass?.simpleName ?: "null"} instead of an object")
    } catch (e: ParseException) {
        throw SyncGateway.Failure(e.code, e.message ?: "Parse error ${e.code}", e)
    }

    private companion object {
        const val PAGE_SIZE = 500
    }
}
