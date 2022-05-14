package dev.capybaralabs.d4j.store.redis.repository

import dev.capybaralabs.d4j.store.common.collectSet
import dev.capybaralabs.d4j.store.common.isPresent
import dev.capybaralabs.d4j.store.common.repository.ChannelRepository
import dev.capybaralabs.d4j.store.redis.RedisFactory
import discord4j.discordjson.json.ChannelData
import org.intellij.lang.annotations.Language
import org.springframework.data.redis.core.script.RedisScript
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

internal class RedisChannelRepository(prefix: String, factory: RedisFactory) : RedisRepository(prefix), ChannelRepository {

	private val channelKey = key("channel")
	private val channelOps = RedisHashOps(channelKey, factory, Long::class.java, ChannelData::class.java)

	private val shardIndexKey = "$channelKey:shard-index"
	private val shardIndex = twoWayIndex(shardIndexKey, factory)

	private val guildIndex = oneWayIndex("$channelKey:guild-index", factory)
	private val gShardIndex = twoWayIndex("$channelKey:guild-shard-index", factory)

	private val evalOps = factory.createRedisOperations<String, String>()

	override fun save(channel: ChannelData, shardId: Int): Mono<Void> {
		return saveAll(listOf(channel), shardId)
	}

	override fun saveAll(channels: List<ChannelData>, shardId: Int): Mono<Void> {
		if (channels.isEmpty()) {
			return Mono.empty()
		}
		// TODO consider LUA script for atomicity
		return Mono.defer {
			val addToShardIndex = shardIndex.addElements(shardId, channels.map { it.id().asLong() })

			val addToGuildIndex = Flux.fromIterable(
				channels
					.filter { it.guildId().isPresent() }
					.groupBy { it.guildId().get().asLong() }
					.map { guildIndex.addElements(it.key, *it.value.map { ch -> ch.id().asLong() }.toTypedArray()) }
			).flatMap { it }

			val guildIds = channels.filter { it.guildId().isPresent() }.map { it.guildId().get().asLong() }
			val addToGuildShardIndex = gShardIndex.addElements(shardId, guildIds)

			val saveChannels = channelOps.putAll(channels.associateBy { it.id().asLong() })


			Mono.`when`(addToShardIndex, addToGuildIndex, addToGuildShardIndex, saveChannels)
		}
	}

	@Language("Lua")
	private val deleteScript = RedisScript.of(
		"""
			local channelKey = KEYS[1]
			local shardIndexKey = KEYS[2]
			local guildIndexKey = KEYS[3] --nullable
			local channelId = ARGV[1]
			-- shardIndex
			redis.call('ZREM', shardIndexKey, channelId)

			-- guildIndex
			if guildIndexKey then
				redis.call('SREM', guildIndexKey, channelId)
			end

			-- channel
			return redis.call('HDEL', channelKey, channelId)
		""".trimIndent(), Long::class.java
	)

	override fun delete(channelId: Long, guildId: Long?): Mono<Long> {
		return evalOps.execute(
			deleteScript,
			listOfNotNull(channelKey, shardIndexKey, guildId?.let { guildIndex.key(it) }),
			listOf(channelId.toString())
		).last()
	}

	override fun deleteByGuildId(guildId: Long): Mono<Long> {
		// TODO consider LUA script for atomicity
		return Mono.defer {
			guildIndex.getElementsInGroup(guildId).collectList()
				.flatMap { channelIdsInGuild ->
					val removeChannels = channelOps.remove(*channelIdsInGuild.toTypedArray())

					val removeFromShardIndex = shardIndex.removeElements(*channelIdsInGuild.toTypedArray())
					val deleteGuildIndexEntry = guildIndex.deleteGroup(guildId)
					val removeGuildFromShardIndex = gShardIndex.removeElements(guildId)

					Mono.`when`(removeFromShardIndex, deleteGuildIndexEntry, removeGuildFromShardIndex)
						.then(removeChannels)
				}
		}
	}

	override fun deleteByShardId(shardId: Int): Mono<Long> {
		// TODO consider LUA script for atomicity
		return Mono.defer {
			val getChannelIds: Mono<Set<Long>> = shardIndex.getElementsByGroup(shardId).collectSet()
				.flatMap { shardIndex.deleteByGroupId(shardId).then(Mono.just(it)) }
			val getGuildIds: Mono<Set<Long>> = gShardIndex.getElementsByGroup(shardId).collectSet()
				.flatMap { gShardIndex.deleteByGroupId(shardId).then(Mono.just(it)) }

			val getAllChannelIds = getGuildIds.flatMap { guildIds ->
				// Technically we don't need to fetch the channel ids of the groups here, we can rely on the shard index only.
				guildIndex.getElementsInGroups(guildIds).collectSet()
					.flatMap { guildIndex.deleteGroups(guildIds).then(Mono.just(it)) }
					.flatMap { idsInGuilds -> getChannelIds.map { channelIds -> idsInGuilds + channelIds } }
			}

			getAllChannelIds.flatMap { channelOps.remove(*it.toTypedArray()) }
		}
	}


	override fun countChannels(): Mono<Long> {
		return Mono.defer {
			channelOps.size()
		}
	}

	override fun countChannelsInGuild(guildId: Long): Mono<Long> {
		return Mono.defer {
			guildIndex.countElementsInGroup(guildId)
		}
	}


	override fun getChannelById(channelId: Long): Mono<ChannelData> {
		return Mono.defer {
			channelOps.get(channelId)
		}
	}

	override fun getChannels(): Flux<ChannelData> {
		return Flux.defer {
			channelOps.values()
		}
	}

	override fun getChannelsInGuild(guildId: Long): Flux<ChannelData> {
		return Flux.defer {
			guildIndex.getElementsInGroup(guildId).collectList()
				.flatMap { channelOps.multiGet(it) }
				.flatMapMany { Flux.fromIterable(it) }
		}
	}
}
