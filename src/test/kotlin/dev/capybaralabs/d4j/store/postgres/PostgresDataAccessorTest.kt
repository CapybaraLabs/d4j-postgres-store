package dev.capybaralabs.d4j.store.postgres

import discord4j.discordjson.Id
import discord4j.discordjson.json.ChannelData
import discord4j.discordjson.json.EmojiData
import discord4j.discordjson.json.GuildCreateData
import discord4j.discordjson.json.ImmutableChannelData
import discord4j.discordjson.json.ImmutableEmojiData
import discord4j.discordjson.json.ImmutableGuildCreateData
import discord4j.discordjson.json.ImmutableMessageData
import discord4j.discordjson.json.ImmutableUserData
import discord4j.discordjson.json.MessageData
import discord4j.discordjson.json.UserData
import discord4j.discordjson.json.gateway.ChannelCreate
import discord4j.discordjson.json.gateway.ChannelDelete
import discord4j.discordjson.json.gateway.GuildCreate
import discord4j.discordjson.json.gateway.MessageCreate
import discord4j.discordjson.possible.Possible
import io.r2dbc.pool.ConnectionPool
import io.r2dbc.pool.ConnectionPoolConfiguration
import io.r2dbc.spi.ConnectionFactories
import java.time.Instant
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicLong
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

private val LONGS = AtomicLong(ThreadLocalRandom.current().nextLong(Long.MAX_VALUE / 2, Long.MAX_VALUE))
fun generateUniqueSnowflakeId(): Long {
	return LONGS.decrementAndGet()
}

internal class PostgresDataAccessorTest {
	private val storeLayout = PostgresStoreLayout(
		ConnectionPool(ConnectionPoolConfiguration
			.builder(ConnectionFactories.get("r2dbc:tc:postgresql:///test?TC_IMAGE_TAG=13"))
			.build()
		)
	)

	private val dataAccessor = storeLayout.dataAccessor
	private val dataUpdater = storeLayout.gatewayDataUpdater

	// TODO write tests for total counts

	@Test
	fun onChannelCreate_onChannelDelete_withoutGuild() {
		val channelId = generateUniqueSnowflakeId()
		val guildId = generateUniqueSnowflakeId()
		val guildCreate = GuildCreate.builder()
			.guild(ds9Guild(guildId).build())
			.build()
		dataUpdater.onGuildCreate(0, guildCreate).blockOptional()

		// Create
		val channelCreate = ChannelCreate.builder()
			.channel(channel(channelId)
				.name("Emergency Medical Holographic Channel")
				.guildId(guildId)
				.build())
		dataUpdater.onChannelCreate(0, channelCreate.build()).block()

		assertThat(dataAccessor.getGuildById(guildId).block()!!.channels())
			.contains(Id.of(channelId))

		assertThat(dataAccessor.countChannelsInGuild(guildId).block()!!)
			.isEqualTo(1)
		assertThat(dataAccessor.getChannelsInGuild(guildId).collectList().block()!!)
			.anyMatch { it.id().asLong() == channelId }

		val channel = dataAccessor.getChannelById(channelId).block()!!
		assertThat(channel.id().asLong()).isEqualTo(channelId)
		assertThat(channel.name().isAbsent).isFalse
		assertThat(channel.name().get()).isEqualTo("Emergency Medical Holographic Channel")
		assertThat(channel.guildId().isAbsent).isFalse
		assertThat(channel.guildId().get().asLong()).isEqualTo(guildId)
		assertThat(dataAccessor.channels.collectList().block()!!)
			.anyMatch { it.id().asLong() == channelId }


		// Send message
		val messageId = generateUniqueSnowflakeId()
		val messageCreate = MessageCreate.builder()
			.message(message(channelId, messageId, generateUniqueSnowflakeId()).build())
			.build()
		dataUpdater.onMessageCreate(0, messageCreate).block()
		assertThat(dataAccessor.getMessagesInChannel(channelId).collectList().block()!!)
			.anyMatch { it.id().asLong() == messageId }
		assertThat(dataAccessor.countMessagesInChannel(channelId).block()!!).isEqualTo(1)


		// Delete
		val channelDelete = ChannelDelete.builder().channel(channel(channelId).guildId(guildId).build())
		dataUpdater.onChannelDelete(0, channelDelete.build()).block()

		val guildAfterDelete = dataAccessor.getGuildById(guildId).block()!!
		assertThat(guildAfterDelete.channels()).doesNotContain(Id.of(channelId))

		assertThat(dataAccessor.countChannelsInGuild(guildId).block()!!).isEqualTo(0)
		assertThat(dataAccessor.getChannelsInGuild(guildId).collectList().block()!!)
			.noneMatch { it.id().asLong() == channelId }

		assertThat(dataAccessor.getChannelById(channelId).block()).isNull()

		assertThat(dataAccessor.getMessagesInChannel(channelId).collectList().block()!!).isEmpty()
		assertThat(dataAccessor.countMessagesInChannel(channelId).block()!!).isEqualTo(0)
	}

	@Test
	fun onChannelCreate_withGuild() {
		val channelId = generateUniqueSnowflakeId()

		// Create
		val channelCreate = ChannelCreate.builder()
			.channel(channel(channelId)
				.name("Emergency Medical Holographic Channel")
				.build())
		dataUpdater.onChannelCreate(0, channelCreate.build()).block()

		val channel = dataAccessor.getChannelById(channelId).block()!!
		assertThat(channel.id().asLong()).isEqualTo(channelId)
		assertThat(channel.name().isAbsent).isFalse
		assertThat(channel.name().get()).isEqualTo("Emergency Medical Holographic Channel")
		assertThat(channel.guildId().isAbsent).isTrue


		// Send message
		val messageId = generateUniqueSnowflakeId()
		val messageCreate = MessageCreate.builder()
			.message(message(channelId, messageId, generateUniqueSnowflakeId()).build())
			.build()
		dataUpdater.onMessageCreate(0, messageCreate).block()
		assertThat(dataAccessor.getMessagesInChannel(channelId).collectList().block()!!)
			.anyMatch { it.id().asLong() == messageId }
		assertThat(dataAccessor.countMessagesInChannel(channelId).block()!!).isEqualTo(1)


		// Delete
		val channelDelete = ChannelDelete.builder().channel(channel(channelId).build())
		dataUpdater.onChannelDelete(0, channelDelete.build()).block()

		assertThat(dataAccessor.getChannelById(channelId).block()).isNull()

		assertThat(dataAccessor.getMessagesInChannel(channelId).collectList().block()!!).isEmpty()
		assertThat(dataAccessor.countMessagesInChannel(channelId).block()!!).isEqualTo(0)
	}


	@Test
	fun onGuildCreate() {
		val guildId = generateUniqueSnowflakeId()
		val guildCreate = GuildCreate.builder()
			.guild(ds9Guild(guildId).build())
			.build()

		dataUpdater.onGuildCreate(0, guildCreate).blockOptional()


		val guild = dataAccessor.getGuildById(guildId).block()!!

		assertThat(guild.id().asLong()).isEqualTo(guildId)
		assertThat(guild.name()).isEqualTo("Deep Space 9")
	}

	@Test
	fun channelsInGuild() {
		val guildId = generateUniqueSnowflakeId()
		val channelIdA = generateUniqueSnowflakeId()
		val channelIdB = generateUniqueSnowflakeId()
		val guildCreate = GuildCreate.builder()
			.guild(
				ds9Guild(guildId)
					.addChannels(
						// simulating real payloads here, they appear to not have guild ids set
						channel(channelIdA).guildId(Possible.absent()).build(),
						channel(channelIdB).guildId(Possible.absent()).build(),
					)
					.build()
			)
			.build()

		dataUpdater.onGuildCreate(0, guildCreate).block()


		val guild = dataAccessor.getGuildById(guildId).block()!!
		assertThat(guild.channels()).hasSize(2)

		val channelA = dataAccessor.getChannelById(channelIdA).block()!!
		assertThat(channelA.id().asLong()).isEqualTo(channelIdA)
		assertThat(channelA.guildId().get().asLong()).isEqualTo(guildId)

		val channelB = dataAccessor.getChannelById(channelIdB).block()!!
		assertThat(channelB.id().asLong()).isEqualTo(channelIdB)
		assertThat(channelB.guildId().get().asLong()).isEqualTo(guildId)

		val count = dataAccessor.countChannelsInGuild(guildId).block()!!
		assertThat(count).isEqualTo(2)
		val channelsInGuild = dataAccessor.getChannelsInGuild(guildId).collectList().block()!!
		assertThat(channelsInGuild)
			.hasSize(2)
			.anySatisfy { assertThat(it.id().asLong()).isEqualTo(channelIdA) }
			.anySatisfy { assertThat(it.id().asLong()).isEqualTo(channelIdB) }
	}

	@Test
	fun emojisInGuild() {
		val guildId = generateUniqueSnowflakeId()
		val emojiIdA = generateUniqueSnowflakeId()
		val emojiIdB = generateUniqueSnowflakeId()
		val guildCreate = GuildCreate.builder()
			.guild(
				ds9Guild(guildId)
					.addEmojis(
						emoji(emojiIdA).build(),
						emoji(emojiIdB).build(),
					)
					.build()
			)
			.build()

		dataUpdater.onGuildCreate(0, guildCreate).block()


		val count = dataAccessor.countEmojisInGuild(guildId).block()!!
		assertThat(count).isEqualTo(2)

		val guild = dataAccessor.getGuildById(guildId).block()!!
		assertThat(guild.emojis()).hasSize(2)

		val emojiA = dataAccessor.getEmojiById(guildId, emojiIdA).block()!!
		assertThat(emojiA.id().get().asLong()).isEqualTo(emojiIdA)

		val emojiB = dataAccessor.getEmojiById(guildId, emojiIdB).block()!!
		assertThat(emojiB.id().get().asLong()).isEqualTo(emojiIdB)
	}

	@Test
	fun onMessageCreate() {
		val messageId = generateUniqueSnowflakeId()
		val channelId = generateUniqueSnowflakeId()
		val authorId = generateUniqueSnowflakeId()

		val messageCreate = MessageCreate.builder()
			.message(message(channelId, messageId, authorId).build())
			.build()

		dataUpdater.onChannelCreate(
			0, ChannelCreate.builder()
			.channel(channel(channelId).build())
			.build()
		).block()

		dataUpdater.onMessageCreate(0, messageCreate).block()

		val message = dataAccessor.getMessageById(channelId, messageId).block()!!
		assertThat(message.id().asLong()).isEqualTo(messageId)
		assertThat(message.channelId().asLong()).isEqualTo(channelId)
		assertThat(message.author().id().asLong()).isEqualTo(authorId)
		assertThat(message.content()).isEqualTo("🖖")

		val count = dataAccessor.countMessagesInChannel(channelId).block()!!
		assertThat(count).isEqualTo(1)

		val channel = dataAccessor.getChannelById(channelId).block()!!
		assertThat(channel.lastMessageId().get().get().asLong()).isEqualTo(messageId)
	}

	private fun message(channelId: Long, messageId: Long, authorId: Long): ImmutableMessageData.Builder {
		return MessageData.builder()
			.id(messageId)
			.channelId(channelId)
			.author(user(authorId).build())
			.timestamp("42")
			.tts(false)
			.mentionEveryone(false)
			.pinned(false)
			.type(2)
			.content("🖖")
	}

	private fun user(userId: Long): ImmutableUserData.Builder {
		return UserData.builder()
			.username("Q")
			.discriminator("6969")
			.id(userId)
	}

	private fun emoji(emojiId: Long): ImmutableEmojiData.Builder {
		return EmojiData.builder()
			.id(emojiId)
	}

	private fun channel(channelId: Long): ImmutableChannelData.Builder {
		return ChannelData.builder()
			.id(channelId)
			.type(2)
	}

	private fun ds9Guild(guildId: Long): ImmutableGuildCreateData.Builder {
		return GuildCreateData.builder()
			.id(guildId)
			.name("Deep Space 9")
			.ownerId(generateUniqueSnowflakeId())
			.verificationLevel(42)
			.region("Alpha Quadrant")
			.afkTimeout(42)
			.defaultMessageNotifications(42)
			.explicitContentFilter(42)
			.mfaLevel(42)
			.premiumTier(42)
			.preferredLocale("Klingon")
			.joinedAt(Instant.now().toString())
			.large(false)
			.memberCount(42)
			.nsfwLevel(69)
	}
}
