package net.wulfnet.ctcl.mixin;

import java.nio.file.Path;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.storage.SavedDataStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Vanilla's own path for a SavedData id, so the corrupt-file guard checks exactly the file vanilla will load. */
@Mixin(SavedDataStorage.class)
public interface SavedDataStorageAccessor {
	@Invoker("getDataFile")
	Path ctcl$getDataFile(Identifier id);
}
