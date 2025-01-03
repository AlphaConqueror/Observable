package observable

import com.mojang.blaze3d.platform.InputConstants
import com.mojang.brigadier.arguments.IntegerArgumentType.getInteger
import com.mojang.brigadier.arguments.IntegerArgumentType.integer
import com.mojang.brigadier.context.CommandContext
import dev.architectury.event.events.client.ClientLifecycleEvent
import dev.architectury.event.events.client.ClientPlayerEvent
import dev.architectury.event.events.client.ClientTickEvent
import dev.architectury.event.events.common.CommandRegistrationEvent
import dev.architectury.event.events.common.LifecycleEvent
import dev.architectury.registry.client.keymappings.KeyMappingRegistry
import dev.architectury.utils.GameInstance
import net.luckperms.api.LuckPerms
import net.luckperms.api.LuckPermsProvider
import net.luckperms.api.model.user.User
import net.luckperms.api.model.user.UserManager
import net.minecraft.ChatFormatting
import net.minecraft.Util
import net.minecraft.client.KeyMapping
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands.argument
import net.minecraft.commands.Commands.literal
import net.minecraft.commands.arguments.DimensionArgument.dimension
import net.minecraft.commands.arguments.DimensionArgument.getDimension
import net.minecraft.commands.arguments.GameProfileArgument.gameProfile
import net.minecraft.commands.arguments.GameProfileArgument.getGameProfiles
import net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos
import net.minecraft.commands.arguments.coordinates.BlockPosArgument.getLoadedBlockPos
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.TextComponent
import net.minecraft.network.chat.TranslatableComponent
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import observable.client.Overlay
import observable.client.ProfileScreen
import observable.net.BetterChannel
import observable.net.C2SPacket
import observable.net.S2CPacket
import observable.server.Profiler
import observable.server.ProfilingData
import observable.server.ServerSettings
import observable.server.TypeMap
import org.apache.logging.log4j.LogManager
import org.lwjgl.glfw.GLFW
import java.util.*

object Observable {
    const val MOD_ID = "observable"
    private const val PERMISSION_NODE = "observable"

    val PROFILE_KEYBIND by lazy {
        KeyMapping(
                "key.observable.profile",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_R,
                "category.observable.keybinds"
        )
    }

    val CHANNEL = BetterChannel(ResourceLocation("channel/observable"))
    val LOGGER = LogManager.getLogger("Observable")
    val PROFILER: Profiler by lazy { Profiler() }
    var RESULTS: ProfilingData? = null
    val PROFILE_SCREEN by lazy { ProfileScreen() }

    fun hasPermission(player: Player): Boolean {
        if (ServerSettings.allPlayersAllowed) return true
        if (ServerSettings.allowedPlayers.contains(player.gameProfile.id.toString())) return true

        // if LuckPerms is loaded, use LuckPerms
        try {
            val luckPerms = LuckPermsProvider.get()
            return getUser(luckPerms, player.gameProfile.id).cachedData
                    .permissionData.checkPermission(PERMISSION_NODE).asBoolean()
        } catch (ignored: IllegalStateException) {
            // ignored
        } catch (ignored: NoClassDefFoundError) {
            // ignored
        }

        if (GameInstance.getServer()?.playerList?.isOp(player.gameProfile) != false) return true
        return GameInstance.getServer()?.isSingleplayer ?: false
    }

    private fun getUser(luckPerms: LuckPerms, uniqueId: UUID): User {
        val userManager: UserManager = luckPerms.userManager
        var user = userManager.getUser(uniqueId)

        if (user == null) {
            user = userManager.loadUser(uniqueId).join()
        }

        return user!!
    }

    @JvmStatic
    fun init() {
        CHANNEL.register { t: C2SPacket.InitTPSProfile, supplier ->
            val player = supplier.get().player
            if (!hasPermission(player)) {
                LOGGER.info("${player.name.contents} lacks permissions to start profiling")
                return@register
            }
            if (PROFILER.notProcessing) {
                PROFILER.runWithDuration(player as? ServerPlayer, t.duration, t.sample) { result ->
                    player.sendMessage(result, Util.NIL_UUID)
                }
            }
            LOGGER.info("${(player.name as TextComponent).text} started profiler for ${t.duration} s")
        }

        CHANNEL.register { t: C2SPacket.RequestTeleport, supplier ->
            println("Using an in-game teleport command has been deprecated")
        }

        CHANNEL.register { t: C2SPacket.RequestAvailability, supplier ->
            (supplier.get().player as? ServerPlayer)?.let {
                CHANNEL.sendToPlayer(
                        it,
                        if (hasPermission(it)) {
                            S2CPacket.Availability.Available
                        } else {
                            S2CPacket.Availability.NoPermissions
                        }
                )
            }
        }

        CHANNEL.register { t: S2CPacket.ProfilingStarted, supplier ->
            PROFILE_SCREEN.action = ProfileScreen.Action.TPSProfilerRunning(t.endMillis)
            PROFILE_SCREEN.startBtn?.active = false
        }

        CHANNEL.register { t: S2CPacket.ProfilingCompleted, supplier ->
            PROFILE_SCREEN.action = ProfileScreen.Action.TPSProfilerCompleted
        }

        CHANNEL.register { t: S2CPacket.ProfilerInactive, supplier ->
            PROFILE_SCREEN.action = ProfileScreen.Action.DEFAULT
            PROFILE_SCREEN.startBtn?.active = true
        }

        CHANNEL.register { t: S2CPacket.ProfilingResult, supplier ->
            RESULTS = t.data
            PROFILE_SCREEN.apply {
                action = ProfileScreen.Action.DEFAULT
                startBtn?.active = true
                arrayOf(editField, overlayBtn).forEach { it.active = true }
            }
            val data = t.data.entities
            LOGGER.info("Received profiling result with ${data.size} entries")
            Overlay.loadSync()
        }

        CHANNEL.register { t: S2CPacket.Availability, supplier ->
            when (t) {
                S2CPacket.Availability.Available -> {
                    PROFILE_SCREEN.action = ProfileScreen.Action.DEFAULT
                    PROFILE_SCREEN.startBtn?.active = true
                }

                S2CPacket.Availability.NoPermissions -> {
                    PROFILE_SCREEN.action = ProfileScreen.Action.NO_PERMISSIONS
                    PROFILE_SCREEN.startBtn?.active = false
                }
            }
        }

        CHANNEL.register { t: S2CPacket.ConsiderProfiling, supplier ->
            if (ProfileScreen.HAS_BEEN_OPENED) return@register
            Observable.LOGGER.info("Notifying player")
            val tps = "%.2f".format(t.tps)
            GameInstance.getClient().gui.chat.addMessage(
                    TranslatableComponent(
                            "text.observable.suggest",
                            tps,
                            TranslatableComponent("text.observable.suggest_action").withStyle(ChatFormatting.UNDERLINE)
                                    .withStyle {
                                        it.withClickEvent(object : ClickEvent(null, "") {
                                            override fun getAction(): Action? {
                                                GameInstance.getClient().setScreen(PROFILE_SCREEN)
                                                return null
                                            }
                                        })
                                    }
                    )
            )
        }

        LifecycleEvent.SERVER_STARTED.register {
            val thread = Thread.currentThread()
            PROFILER.serverThread = thread
//            ContinuousPerfEval.start()
            LOGGER.info("Registered thread ${thread.name}")
        }

        CommandRegistrationEvent.EVENT.register { dispatcher, dedicated ->
            val cmd = literal("observable")
                    .requires { it.hasPermission(4) }
                    .executes {
                        it.source.sendSuccess(TextComponent(ServerSettings.toString()), false)
                        1
                    }
                    .then(
                            literal("run").then(
                                    argument("duration", integer()).executes { ctx ->
                                        val duration = getInteger(ctx, "duration")
                                        PROFILER.runWithDuration(ctx.source.entity as? ServerPlayer, duration, false) { result ->
                                            ctx.source.sendSuccess(result, false)
                                        }
                                        ctx.source.sendSuccess(TranslatableComponent("text.observable.profile_started", duration), false)
                                        1
                                    }
                            )
                    )
                    .then(
                            literal("allow").then(
                                    argument("player", gameProfile()).executes { ctx ->
                                        getGameProfiles(ctx, "player").forEach { player ->
                                            ServerSettings.allowedPlayers.add(player.id.toString())
                                            GameInstance.getServer()?.playerList?.getPlayer(player.id)?.let {
                                                CHANNEL.sendToPlayer(it, S2CPacket.Availability.Available)
                                            }
                                        }
                                        ServerSettings.sync()
                                        1
                                    }
                            )
                    )
                    .then(
                            literal("deny").then(
                                    argument("player", gameProfile()).executes { ctx ->
                                        getGameProfiles(ctx, "player").forEach { player ->
                                            ServerSettings.allowedPlayers.remove(player.id.toString())
                                            GameInstance.getServer()?.playerList?.getPlayer(player.id)?.let {
                                                CHANNEL.sendToPlayer(it, S2CPacket.Availability.NoPermissions)
                                            }
                                        }
                                        ServerSettings.sync()
                                        1
                                    }
                            )
                    )
                    .then(
                            literal("set").let {
                                ServerSettings::class.java.declaredFields.fold(it) { setCmd, field ->
                                    val argType = TypeMap[field.type] ?: return@fold setCmd
                                    setCmd.then(
                                            literal(field.name).then(
                                                    argument("newVal", argType())
                                                            .executes { ctx ->
                                                                try {
                                                                    field.isAccessible = true
                                                                    field.set(ServerSettings, ctx.getArgument("newVal", field.type))
                                                                    ServerSettings.sync()
                                                                    1
                                                                } catch (e: Exception) {
                                                                    e.printStackTrace()
                                                                    ctx.source.sendFailure(TextComponent("Error setting value\n$e"))
                                                                    0
                                                                }
                                                            }
                                            )
                                    )
                                }
                            }
                    )
                    .then(
                            literal("tp").then(
                                    argument("dim", dimension())
                                            .then(
                                                    literal("entity").then(
                                                            argument("id", integer()).executes { ctx ->
                                                                withDimMove(ctx) { player, level ->
                                                                    val id = getInteger(ctx, "id")
                                                                    level.getEntity(id)?.position()?.apply {
                                                                        LOGGER.info("Moving to ($x, $y, $z) in $level")
                                                                        player.moveTo(this)
                                                                    }
                                                                            ?: player.displayClientMessage(
                                                                                    TranslatableComponent("text.observable.entity_not_found", level.toString()),
                                                                                    true
                                                                            )
                                                                }
                                                                1
                                                            }
                                                    )
                                            )
                                            .then(
                                                    literal("position")
                                                            .then(
                                                                    argument("pos", blockPos()).executes { ctx ->
                                                                        withDimMove(ctx) { player, _ ->
                                                                            val pos = getLoadedBlockPos(ctx, "pos")
                                                                            player.moveTo(pos, 0F, 0F)
                                                                        }
                                                                        1
                                                                    }
                                                            )

                                            )
                            )
                    )

            dispatcher.register(cmd)
        }
    }

    fun withDimMove(ctx: CommandContext<CommandSourceStack>, block: (Player, Level) -> Unit) {
        val player = ctx.source.playerOrException
        val level = getDimension(ctx, "dim")

        Scheduler.SERVER.enqueue {
            if (player.level != level) {
                with(player.position()) {
                    (player as ServerPlayer).teleportTo(
                            level,
                            x,
                            y,
                            z,
                            player.rotationVector.x,
                            player.rotationVector.y
                    )
                }
            }

            block(player, level)
        }
    }

    @JvmStatic
    fun clientInit() {
        KeyMappingRegistry.register(PROFILE_KEYBIND)

        ClientTickEvent.CLIENT_POST.register {
            if (PROFILE_KEYBIND.consumeClick()) {
                it.setScreen(PROFILE_SCREEN)
            }
        }

        ClientLifecycleEvent.CLIENT_LEVEL_LOAD.register {
            Overlay.loadSync(it)
        }

        ClientPlayerEvent.CLIENT_PLAYER_QUIT.register {
            RESULTS = null
            PROFILE_SCREEN.action = ProfileScreen.Action.UNAVAILABLE
        }
    }
}
