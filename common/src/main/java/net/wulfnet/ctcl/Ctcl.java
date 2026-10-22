package net.wulfnet.ctcl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Constants every loader shares. Each loader's entrypoint wires its events to {@code common}. */
public final class Ctcl {
	public static final String MOD_ID = "ctcl";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private Ctcl() {
	}
}
