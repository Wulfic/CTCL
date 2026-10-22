package net.wulfnet.ctcl.mixin;

import java.nio.file.Path;
import net.minecraft.world.level.storage.DimensionDataStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Vanilla's own path for a SavedData id, so the corrupt-file guard checks exactly the file vanilla will load. */
@Mixin(DimensionDataStorage.class)
public interface DimensionDataStorageAccessor {
	@Invoker("getDataFile")
	Path ctcl$getDataFile(String id);
}
