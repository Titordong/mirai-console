/*
 * Copyright 2019-2020 Mamoe Technologies and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 with Mamoe Exceptions 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AFFERO GENERAL PUBLIC LICENSE version 3 with Mamoe Exceptions license that can be found via the following link.
 *
 * https://github.com/mamoe/mirai/blob/master/LICENSE
 */

package net.mamoe.mirai.console.internal.command

import net.mamoe.mirai.console.command.*
import net.mamoe.mirai.console.command.description.CommandArgumentParserException
import net.mamoe.mirai.contact.Group
import net.mamoe.mirai.contact.Member
import net.mamoe.mirai.contact.nameCardOrNick
import kotlin.math.max


internal infix fun Array<String>.matchesBeginning(list: List<Any>): Boolean {
    this.forEachIndexed { index, any ->
        if (list[index] != any) return false
    }
    return true
}

internal infix fun Array<out String>.intersectsIgnoringCase(other: Array<out String>): Boolean {
    val max = this.size.coerceAtMost(other.size)
    for (i in 0 until max) {
        if (this[i].equals(other[i], ignoreCase = true)) return true
    }
    return false
}

internal fun String.fuzzyMatchWith(target: String): Double {
    if (this == target) {
        return 1.0
    }
    var match = 0
    for (i in 0..(max(this.lastIndex, target.lastIndex))) {
        val t = target.getOrNull(match)
        if (t == this.getOrNull(i) && t != null) {
            match++
        }
    }
    return match.toDouble() / (max(this.lastIndex, target.lastIndex) + 1)
}

internal inline fun <T : Any> Collection<T>.fuzzySearch(
    target: String,
    crossinline index: (T) -> String
): T? {
    var maxElement: T? = null
    var max = 0.0

    for (t in this) {
        val r = index(t).fuzzyMatchWith(target)
        if (r > max) {
            maxElement = t
            max = r
        }
    }

    if (max >= 0.7) {
        return maxElement
    }
    return null
}

/**
 * 模糊搜索一个List中index最接近target的东西
 * 并且确保target是唯一的
 * 如搜索index为XXXXYY list中同时存在XXXXYYY XXXXYYYY 将返回null
 */
internal inline fun <T : Any> Collection<T>.fuzzySearchOnly(
    target: String,
    index: (T) -> String
): T? {
    var potential: T? = null
    var rate = 0.0
    var collide = 0
    this.forEach {
        with(index(it).fuzzyMatchWith(target)) {
            if (this > rate) {
                rate = this
                potential = it
            }
            if (this == 1.0) {
                collide++
            }
            if (collide > 1) {
                return null//collide
            }
        }
    }
    return potential
}


internal fun Group.fuzzySearchMember(nameCardTarget: String): Member? {
    return this.members.fuzzySearch(nameCardTarget) { it.nameCardOrNick }
}


//// internal

@JvmSynthetic
internal inline fun <reified T> List<T>.dropToTypedArray(n: Int): Array<T> = Array(size - n) { this[n + it] }

@JvmSynthetic
@Throws(CommandExecutionException::class)
internal suspend fun CommandSender.executeCommandInternal(
    command: Command,
    args: Array<out Any>,
    commandName: String,
    checkPermission: Boolean
) {
    if (checkPermission && !command.testPermission(this)) {
        throw CommandExecutionException(this, command, commandName, CommandPermissionDeniedException(this, command))
    }

    kotlin.runCatching {
        command.onCommand(this, args)
    }.onFailure {
        catchExecutionException(it)
        if (it !is CommandArgumentParserException) {
            throw CommandExecutionException(this, command, commandName, it)
        }
    }
}


@JvmSynthetic
internal suspend fun CommandSender.executeCommandInternal(
    messages: Any,
    commandName: String
): CommandExecuteResult {
    val command =
        CommandManagerImpl.matchCommand(commandName) ?: return CommandExecuteResult.CommandNotFound(commandName)
    val args = messages.flattenCommandComponents().dropToTypedArray(1)

    if (!command.testPermission(this)) {
        return CommandExecuteResult.PermissionDenied(command, commandName)
    }
    kotlin.runCatching {
        command.onCommand(this, args)
    }.fold(
        onSuccess = {
            return CommandExecuteResult.Success(
                commandName = commandName,
                command = command,
                args = args
            )
        },
        onFailure = {
            return CommandExecuteResult.ExecutionFailed(
                commandName = commandName,
                command = command,
                exception = it,
                args = args
            )
        }
    )
}
