package net.wulfnet.ctcl.server;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.wulfnet.ctcl.core.CorruptDataGuard;
import net.wulfnet.ctcl.core.LoaderService;
import net.wulfnet.ctcl.core.LoaderTickets;
import net.wulfnet.ctcl.mixin.DimensionDataStorageAccessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Server-side lifecycle: one {@link LoaderService} per loaded dimension. Server thread only. */
public final class CtclServer {
	/** Ticks between validation passes. */
	static final int VALIDATE_INTERVAL = 20;

	private static final Logger LOGGER = LoggerFactory.getLogger("ctcl");
	private static final Map<ResourceKey<Level>, LoaderService> SERVICES = new HashMap<>();

	private CtclServer() {
	}

	public static void init() {
		CtclTickets.register();
		ServerWorldEvents.LOAD.register((server, level) -> load(level));
		ServerWorldEvents.UNLOAD.register((server, level) -> unload(level));
		ServerTickEvents.END_WORLD_TICK.register(CtclServer::tick);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> SERVICES.clear());
	}

	public static Optional<LoaderService> service(ServerLevel level) {
		return Optional.ofNullable(SERVICES.get(level.dimension()));
	}

	/**
	 * The service for a level that must have one, or empty with a WARN. Every loaded level
	 * gets a service in {@link #load}, so a miss means the load/unload wiring is broken.
	 */
	static Optional<LoaderService> requireService(ServerLevel level, String action) {
		Optional<LoaderService> service = service(level);
		if (service.isEmpty()) {
			LOGGER.warn("No loader service for {} while handling {}; ignoring it", level.dimension().identifier(), action);
		}
		return service;
	}

	public static void onTableBlockRemoved(ServerLevel level, BlockPos pos) {
		requireService(level, "removal of the table at " + pos.toShortString())
			.ifPresent(service -> service.remove(LoaderData.toTablePos(pos), "block removed"));
	}

	private static void load(ServerLevel level) {
		String dimension = level.dimension().identifier().toString();
		DimensionDataStorage storage = level.getDataStorage();
		Path file = ((DimensionDataStorageAccessor) storage).ctcl$getDataFile(LoaderData.TYPE.id());
		CorruptDataGuard.Outcome outcome = CorruptDataGuard.check(file, path -> isReadable(storage, path), Clock.systemDefaultZone());
		LoaderData data;
		if (outcome == CorruptDataGuard.Outcome.BACKUP_FAILED) {
			// Fail closed: never hand vanilla an instance it would save over the unreadable file.
			data = new LoaderData();
		} else {
			data = storage.computeIfAbsent(LoaderData.TYPE);
		}
		LoaderService service = new LoaderService(dimension, data, new LoaderTickets(new CtclTickets.VanillaSink(level.getChunkSource())));
		SERVICES.put(level.dimension(), service);
		service.restore();
	}

	private static boolean isReadable(DimensionDataStorage storage, Path file) {
		CompoundTag root;
		try {
			root = storage.readTagFromDisk(LoaderData.TYPE.id(), null, SharedConstants.getCurrentVersion().dataVersion().version());
		} catch (IOException e) {
			LOGGER.error("Could not read {}", file, e);
			return false;
		}
		Tag data = root.get("data");
		if (data == null) {
			LOGGER.error("{} has no 'data' tag", file);
			return false;
		}
		List<String> problems = LoaderData.problems(data);
		problems.forEach(problem -> LOGGER.error("{}: {}", file, problem));
		return problems.isEmpty();
	}

	private static void unload(ServerLevel level) {
		LoaderService service = SERVICES.remove(level.dimension());
		if (service != null) {
			service.unload();
		}
	}

	private static void tick(ServerLevel level) {
		if (level.getGameTime() % VALIDATE_INTERVAL != 0) {
			return;
		}
		service(level).ifPresent(service -> service.validate(
			pos -> level.isLoaded(LoaderData.toBlockPos(pos)),
			pos -> level.getBlockState(LoaderData.toBlockPos(pos)).is(Blocks.CRAFTING_TABLE)
		));
	}
}
