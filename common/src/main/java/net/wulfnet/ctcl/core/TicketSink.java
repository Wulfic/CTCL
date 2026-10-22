package net.wulfnet.ctcl.core;

/**
 * Where chunk tickets actually go. The Minecraft implementation adds and removes vanilla
 * tickets; tests pass a Mockito mock. Callers guarantee each add is balanced by exactly
 * one remove with the same arguments.
 */
public interface TicketSink {
	void add(int chunkX, int chunkZ, int ticketRadius);

	void remove(int chunkX, int chunkZ, int ticketRadius);
}
