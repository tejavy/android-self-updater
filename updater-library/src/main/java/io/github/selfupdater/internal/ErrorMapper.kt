package io.github.selfupdater.internal

import io.github.selfupdater.UnexpectedUpdaterException
import io.github.selfupdater.UpdateNetworkException
import io.github.selfupdater.UpdaterException
import java.io.IOException

internal object ErrorMapper {
    fun map(error: Throwable, operation: String): UpdaterException = when (error) {
        is UpdaterException -> error
        is IOException -> UpdateNetworkException("Network failure while $operation", error)
        else -> UnexpectedUpdaterException(error)
    }
}

