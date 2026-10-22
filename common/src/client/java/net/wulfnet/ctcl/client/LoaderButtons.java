package net.wulfnet.ctcl.client;

import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.network.chat.Component;
import net.wulfnet.ctcl.client.mixin.AbstractContainerScreenAccessor;
import net.wulfnet.ctcl.client.mixin.AbstractRecipeBookScreenAccessor;
import net.wulfnet.ctcl.core.LoaderSize;
import net.wulfnet.ctcl.core.SizeSlot;
import net.wulfnet.ctcl.net.CycleLoaderC2S;

/**
 * The size button on the crafting table screen. Shows only the number the server last sent.
 * Each loader calls {@link #attach} after a crafting screen initialises (only when the server can
 * receive {@link CycleLoaderC2S}), adds the returned widget, and calls {@link #refresh} before every frame.
 */
public final class LoaderButtons {
	static final int WIDTH = 20;
	static final int HEIGHT = 14;
	/** Top-right inside the GUI: clear of the title, the grid, the result slot and the recipe-book button. */
	static final int RIGHT_INSET = 28;
	static final int TOP_INSET = 4;

	private static final SizeSlot SIZES = new SizeSlot();
	private static final Map<Screen, Button> BUTTONS = new WeakHashMap<>();

	private LoaderButtons() {
	}

	public static void onSize(int containerId, int side) {
		SIZES.accept(containerId, side);
	}

	/** Container ids restart per connection, so a stale size could match a new world's first menu. Client thread only. */
	public static void clear() {
		SIZES.clear();
	}

	/** The button on this screen, if it has one. For tests. */
	public static Optional<Button> find(Screen screen) {
		return Optional.ofNullable(BUTTONS.get(screen));
	}

	/** Creates this screen's button. The caller adds it to the screen. */
	public static Button attach(CraftingScreen screen, Consumer<CycleLoaderC2S> sender) {
		int containerId = screen.getMenu().containerId;
		Button button = Button.builder(Component.empty(), b -> sender.accept(new CycleLoaderC2S(containerId)))
			.size(WIDTH, HEIGHT)
			.build();
		sync(screen, button, containerId);
		BUTTONS.put(screen, button);
		return button;
	}

	/** Every frame: the recipe book moves leftPos, and the server's reply can land at any time. */
	public static void refresh(CraftingScreen screen) {
		Button button = BUTTONS.get(screen);
		if (button != null) {
			sync(screen, button, screen.getMenu().containerId);
		}
	}

	private static void sync(CraftingScreen screen, Button button, int containerId) {
		AbstractContainerScreenAccessor layout = (AbstractContainerScreenAccessor) screen;
		AbstractRecipeBookScreenAccessor book = (AbstractRecipeBookScreenAccessor) screen;
		// A narrow window draws the open recipe book over the GUI; the button would sit on top of it.
		button.visible = !(book.ctcl$isWidthTooNarrow() && book.ctcl$getRecipeBookComponent().isVisible());
		button.setPosition(layout.ctcl$getLeftPos() + layout.ctcl$getImageWidth() - RIGHT_INSET, layout.ctcl$getTopPos() + TOP_INSET);
		Optional<LoaderSize> size = SIZES.sizeFor(containerId);
		// Disabled and blank until the server has said: never show a guessed value.
		button.active = size.isPresent();
		button.setMessage(size.map(s -> Component.literal(Integer.toString(s.side()))).orElse(Component.empty()));
	}
}
