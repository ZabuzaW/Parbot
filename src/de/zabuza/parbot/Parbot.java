package de.zabuza.parbot;

import java.awt.AWTException;
import java.awt.Image;
import java.io.File;
import java.io.IOException;

import javax.imageio.ImageIO;

import org.openqa.selenium.remote.DesiredCapabilities;

import com.detectlanguage.DetectLanguage;

import de.zabuza.brainbridge.client.BrainBridgeClient;
import de.zabuza.parbot.exceptions.EmptyUserCredentialsException;
import de.zabuza.parbot.logging.ILogger;
import de.zabuza.parbot.logging.LoggerFactory;
import de.zabuza.parbot.logging.LoggerUtil;
import de.zabuza.parbot.service.Service;
import de.zabuza.parbot.settings.IBotSettingsProvider;
import de.zabuza.parbot.settings.IBrowserSettingsProvider;
import de.zabuza.parbot.settings.IUserSettingsProvider;
import de.zabuza.parbot.settings.SettingsController;
import de.zabuza.parbot.tray.TrayManager;
import de.zabuza.sparkle.IFreewarAPI;
import de.zabuza.sparkle.Sparkle;
import de.zabuza.sparkle.freewar.EWorld;
import de.zabuza.sparkle.freewar.IFreewarInstance;
import de.zabuza.sparkle.webdriver.EBrowser;

/**
 * The entry class of the Parbot service. After creation and initialization via
 * {@link #initialize()} the tool can be started by {@link #start()} and ended
 * by {@link #shutdown()} or {@link #stop()}.
 * 
 * @author Zabuza {@literal <zabuza.dev@gmail.com>}
 *
 */
public final class Parbot {
	/**
	 * The file path to the image of the icon to use.
	 */
	private static final String IMAGE_PATH_ICON = "res/img/icon.png";

	/**
	 * Starts the Parbot service and ensures that all thrown and not caught
	 * exceptions create log messages and shutdown the service.
	 * 
	 * @param args
	 *            Not supported
	 */
	public static void main(final String[] args) {
		Parbot parbot = null;
		try {
			parbot = new Parbot();
			parbot.initialize();
			parbot.start();
		} catch (final Exception e) {
			LoggerFactory.getLogger().logError("Error, shutting down: " + LoggerUtil.getStackTrace(e));
			// Try to shutdown
			if (parbot != null) {
				parbot.shutdown();
			}
		}
	}

	/**
	 * The Freewar API to use for creation of Freewar instances.
	 */
	private IFreewarAPI mApi;
	/**
	 * The image of the icon to use.
	 */
	private Image mIconImage;
	/**
	 * The Freewar instance to use for interaction with the game.
	 */
	private IFreewarInstance mInstance;
	/**
	 * The logger to use for logging.
	 */
	private final ILogger mLogger;
	/**
	 * The main service of the tool.
	 */
	private Service mService;
	/**
	 * The controller of the settings.
	 */
	private final SettingsController mSettingsController;
	/**
	 * The tray manager to use which manages the tray icon of the tool.
	 */
	private TrayManager mTrayManager;
	/**
	 * Whether the tool was shutdown using {@link #shutdown()}.
	 */
	private boolean mWasShutdown;

	/**
	 * Creates a new instance of the service. After creation call
	 * {@link #initialize()} and then {@link #start()}. To end the service call
	 * {@link #shutdown()} or {@link #stop()}.
	 * 
	 */
	public Parbot() {
		this.mInstance = null;
		this.mApi = null;
		this.mTrayManager = null;
		this.mService = null;
		this.mSettingsController = new SettingsController();
		this.mLogger = LoggerFactory.getLogger();
		this.mWasShutdown = false;
	}

	/**
	 * Initializes the service. Call this method prior to {@link #start()}.
	 * 
	 * @throws IOException
	 *             If an I/O-Exception occurs when reading the icon image
	 * @throws AWTException
	 *             If the desktop system tray is missing
	 */
	public void initialize() throws IOException, AWTException {
		if (this.mLogger.isDebugEnabled()) {
			this.mLogger.logDebug("Initializing Parbot");
		}
		// Add shutdown hook for a controlled shutdown when killed
		Runtime.getRuntime().addShutdownHook(new ShutdownHook(this));

		this.mSettingsController.initialize();

		this.mIconImage = ImageIO.read(new File(IMAGE_PATH_ICON));
		this.mTrayManager = new TrayManager(this, this.mIconImage);
		this.mTrayManager.addTrayIcon();
	}

	/**
	 * Shuts the service down and frees all used resources. The object instance
	 * can not be used anymore after calling this method, instead create a new
	 * one. If the service should only get restarted consider using
	 * {@link #stop()} instead of this method.
	 */
	public void shutdown() {
		this.mLogger.flush();
		if (this.mLogger.isDebugEnabled()) {
			this.mLogger.logDebug("Shutting down Parbot");
		}
		try {
			stop();
		} catch (final Exception e) {
			this.mLogger.logError("Error while stopping: " + LoggerUtil.getStackTrace(e));
		}

		if (this.mTrayManager != null) {
			try {
				this.mTrayManager.removeTrayIcon();
			} catch (final Exception e) {
				this.mLogger.logError("Error while removing tray icon: " + LoggerUtil.getStackTrace(e));
			}
		}

		this.mWasShutdown = true;

		this.mLogger.logInfo("Parbot shutdown");
		this.mLogger.close();
	}

	/**
	 * Starts the service. Prior to this call {@link #initialize()}. To end the
	 * tool call {@link #shutdown()} or {@link #stop()}.
	 */
	public void start() {
		this.mLogger.logInfo("Parbot start");
		startService(this.mSettingsController, this.mSettingsController, this.mSettingsController);
	}

	/**
	 * Starts the actual main service of the tool. The method tries to catch all
	 * not caught exceptions to ensure a proper shutdown of the tool.
	 * 
	 * @param botSettingsProvider
	 *            Object that provides settings about bot settings to use
	 * @param userSettingsProvider
	 *            Object that provides settings about the Freewar user to use
	 *            for the tool
	 * @param browserSettingsProvider
	 *            Object that provides settings about the browser to use for the
	 *            tool
	 * @throws EmptyUserCredentialsException
	 *             If user settings like name or password are empty or
	 *             <tt>null</tt>
	 */
	public void startService(final IBotSettingsProvider botSettingsProvider,
			final IUserSettingsProvider userSettingsProvider, final IBrowserSettingsProvider browserSettingsProvider)
					throws EmptyUserCredentialsException {
		try {
			this.mLogger.logInfo("Starting service");

			final String username = userSettingsProvider.getUserName();
			final String password = userSettingsProvider.getPassword();
			final EWorld world = userSettingsProvider.getWorld();
			final String emptyText = "";
			if (username == null || username.equals(emptyText) || password == null || password.equals(emptyText)
					|| world == null) {
				throw new EmptyUserCredentialsException();
			}

			// Set the language detection API key
			DetectLanguage.apiKey = botSettingsProvider.getLanguageDetectionAPIKey();

			// Create Freewar API
			final EBrowser browser = browserSettingsProvider.getBrowser();
			// No need for a delayed web driver since we will only click the
			// chat button once in a time
			this.mApi = new Sparkle(browser, false);

			// Set options
			final DesiredCapabilities capabilities = this.mApi.createCapabilities(browser,
					browserSettingsProvider.getDriverForBrowser(browser), browserSettingsProvider.getBrowserBinary(),
					browserSettingsProvider.getUserProfile());
			this.mApi.setCapabilities(capabilities);

			// Login and create an instance
			this.mInstance = this.mApi.login(username, password, world);

			// Create and start all services
			final BrainBridgeClient brainBridge = new BrainBridgeClient(botSettingsProvider.getServerAddress(),
					botSettingsProvider.getPort().intValue());
			this.mService = new Service(this.mApi, this.mInstance, brainBridge, botSettingsProvider, this);
			this.mService.start();
		} catch (final Exception e) {
			this.mLogger.logError("Error while starting service, shutting down: " + LoggerUtil.getStackTrace(e));
			// Try to shutdown and free all resources
			if (this.mInstance != null && this.mApi != null) {
				this.mApi.logout(this.mInstance, true);
			}
			if (this.mApi != null) {
				this.mApi.shutdown(true);
			}

			shutdown();
		}
	}

	/**
	 * Stops the service. In contrast to {@link #shutdown()} the service object
	 * can be restarted with {@link #start()} after this method.
	 */
	public void stop() {
		try {
			stopService();
		} catch (final Exception e) {
			this.mLogger.logError("Error while stopping service: " + LoggerUtil.getStackTrace(e));
		}
	}

	/**
	 * Whether the tool was shutdown using {@link #shutdown()}. If it was it
	 * should not be used anymore.
	 * 
	 * @return <tt>True</tt> if the tool was shutdown, <tt>false</tt> if not
	 */
	public boolean wasShutdown() {
		return this.mWasShutdown;
	}

	/**
	 * Stops the actual main service of the tool if present and active. The
	 * service can not be used anymore after calling this method. Instead
	 * restart the tool by calling {@link #stop()} and {@link #start()}.
	 */
	private void stopService() {
		if (this.mService != null && this.mService.isActive()) {
			try {
				this.mService.stopService();
				// Wait for the service to stop
				this.mService.join();
			} catch (final Exception e) {
				this.mLogger.logError("Error while stopping service: " + LoggerUtil.getStackTrace(e));
			}

			this.mLogger.flush();
		}
	}
}
