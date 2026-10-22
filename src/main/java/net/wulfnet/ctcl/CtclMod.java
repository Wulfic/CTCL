package net.wulfnet.ctcl;

import net.fabricmc.api.ModInitializer;
import net.wulfnet.ctcl.server.CtclNetworking;
import net.wulfnet.ctcl.server.CtclServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CtclMod implements ModInitializer {
	public static final String MOD_ID = "ctcl";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		CtclServer.init();
		CtclNetworking.init();
		LOGGER.info("Crafting Table Chunk Loader initialised");
	}
}
