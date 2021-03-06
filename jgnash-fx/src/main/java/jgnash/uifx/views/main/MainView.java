/*
 * jGnash, a personal finance application
 * Copyright (C) 2001-2020 Craig Cavanaugh
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package jgnash.uifx.views.main;

import java.io.File;
import java.text.MessageFormat;
import java.util.ResourceBundle;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.prefs.Preferences;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.WeakChangeListener;
import javafx.concurrent.Task;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.MenuBar;
import javafx.scene.control.ToolBar;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import jgnash.engine.Engine;
import jgnash.engine.EngineFactory;
import jgnash.engine.message.MessageBus;
import jgnash.engine.message.MessageChannel;
import jgnash.engine.message.MessageListener;
import jgnash.net.security.UpdateFactory;
import jgnash.plugin.FxPlugin;
import jgnash.plugin.Plugin;
import jgnash.plugin.PluginFactory;
import jgnash.resource.util.ResourceUtils;
import jgnash.resource.util.Version;
import jgnash.uifx.Options;
import jgnash.uifx.StaticUIMethods;
import jgnash.uifx.control.BusyPane;
import jgnash.uifx.control.StatusBar;
import jgnash.uifx.control.TabViewPane;
import jgnash.uifx.resource.font.FontAwesomeLabel;
import jgnash.uifx.skin.ThemeManager;
import jgnash.uifx.tasks.BootEngineTask;
import jgnash.uifx.tasks.CloseFileTask;
import jgnash.uifx.util.FXMLUtils;
import jgnash.uifx.util.JavaFXUtils;
import jgnash.uifx.util.StageUtils;
import jgnash.uifx.views.accounts.AccountsViewController;
import jgnash.uifx.views.budget.BudgetViewController;
import jgnash.uifx.views.recurring.RecurringViewController;
import jgnash.uifx.views.register.RegisterViewController;
import jgnash.util.DefaultDaemonThreadFactory;
import jgnash.util.NotNull;
import jgnash.util.Nullable;

/**
 * JavaFX version of jGnash.
 *
 * @author Craig Cavanaugh
 */
public class MainView implements MessageListener {

    private static final String TITLE;

    private static final String LAST_TAB = "lastTab";

    private static final Logger logger = Logger.getLogger(MainView.class.getName());

    private final ResourceBundle resources = ResourceUtils.getBundle();

    private final Executor backgroundExecutor = Executors
        .newSingleThreadExecutor(new DefaultDaemonThreadFactory("Main View Background Executor"));

    private final StatusBarLogHandler statusBarLogHandler = new StatusBarLogHandler();

    private Stage primaryStage;

    private StatusBar statusBar;

    private TabViewPane tabViewPane;

    private MenuBar menuBar;

    private BusyPane busyPane;

    private final Preferences preferences = Preferences.userNodeForPackage(MainView.class);

    // Maintain a reference for removal to prevent memory leaks
    private ChangeListener<Number> tabListener;

    /**
     * Application Singleton.
     */
    private static MainView instance;

    static {
        TITLE = Version.getAppName() + " - " + Version.getAppVersion();
    }

    public MainView() {
        if (instance == null) {
            instance = this;
        }
    }

    public static MainView getInstance() {
        return instance;
    }

    @NotNull
    public static Logger getLogger() {
        return logger;
    }

    /**
     * Allows lookup for a scene node.  Intended for plugin use.
     *
     * @param selector node id to look for
     *
     * @return the first first node with the matching id.  Returns null if not found
     * @see Node#lookup(String)
     */
    public Node lookup(@NotNull final String selector) {
        return this.primaryStage.getScene().lookup(selector);
    }

    /**
     * Provides access to the application MenuBar for plugins.
     *
     * @return Application {@code MenuBar}
     */
    public MenuBar getMenuBar() {
        return this.menuBar;
    }

    /**
     *
     * @param stage
     * @param dataFile
     * @param password
     * @param host
     * @param port
     * @throws java.lang.Exception
     */
    public void start(final javafx.stage.Stage stage,
        @Nullable final File dataFile,
        final char[] password,
        @Nullable final String host,
        final int port)
        throws java.lang.Exception {
        ThemeManager.restoreLastUsedTheme();

        this.primaryStage = stage;

        this.busyPane = new BusyPane();

        try {
            final FXMLLoader fxmlLoader = new FXMLLoader(MenuBarController.class.getResource("MainMenuBar.fxml"), this.resources);
            this.menuBar = fxmlLoader.load();
        } catch (final Exception e) {
            logger.log(Level.SEVERE, e.getLocalizedMessage(), e);
        }

        final ToolBar mainToolBar = FXMLLoader.load(MainToolBarController.class.getResource("MainToolBar.fxml"), this.resources);

        this.tabViewPane = new TabViewPane();

        final VBox top = new VBox();
        top.getChildren().addAll(this.menuBar, mainToolBar);
        top.setFillWidth(true);

        this.statusBar = new StatusBar();

        final BorderPane borderPane = new BorderPane();
        borderPane.setTop(top);
        borderPane.setCenter(this.tabViewPane);
        borderPane.setBottom(this.statusBar);

        final StackPane stackPane = new StackPane();
        stackPane.getChildren().addAll(borderPane, this.busyPane);

        final Scene scene = new Scene(stackPane, 640, 480);
        ThemeManager.applyStyleSheets(scene);

        scene.getRoot().styleProperty().bind(ThemeManager.styleProperty());

        stage.setTitle(TITLE);
        stage.getIcons().add(StaticUIMethods.getApplicationIcon());
        stage.setScene(scene);
        stage.setResizable(true);

        // enforce a min width to prevent it from disappearing with a bad click and drag
        stage.setMinWidth(640);
        stage.setMinHeight(480);

        installHandlers();

        MessageBus.getInstance().registerListener(this, MessageChannel.SYSTEM);

        StageUtils.addBoundsListener(stage, MainView.class);

        stage.show();

        Engine.addLogHandler(this.statusBarLogHandler);
        EngineFactory.addLogHandler(this.statusBarLogHandler);
        UpdateFactory.addLogHandler(this.statusBarLogHandler);
        logger.addHandler(this.statusBarLogHandler); // listen to my own logger

        stage.toFront();
        stage.requestFocus();

        if (host != null) { // connect to a remote server instead of loading a local file
            new Thread(() -> {
                try {
                    Thread.sleep(BootEngineTask.FORCED_DELAY);
                    this.backgroundExecutor
                        .execute(() -> JavaFXUtils.runLater(() -> BootEngineTask.initiateBoot(null, password, true, host, port)));
                } catch (final InterruptedException e) {
                    logger.log(Level.SEVERE, e.getLocalizedMessage(), e);
                    Thread.currentThread().interrupt();
                }
            }).start();
        } else if (dataFile != null) { // Load the specified file, this overrides the last open file
            new Thread(() -> {
                try {
                    Thread.sleep(BootEngineTask.FORCED_DELAY);
                    this.backgroundExecutor
                        .execute(() -> JavaFXUtils
                            .runLater(() -> BootEngineTask.initiateBoot(dataFile.getAbsolutePath(), password, false, null, 0)));
                } catch (final InterruptedException e) {
                    logger.log(Level.SEVERE, e.getLocalizedMessage(), e);
                    Thread.currentThread().interrupt();
                }
            }).start();
        } else if (Options.openLastProperty().get()) { // Load the last open file if enabled
            new Thread(() -> {
                try {
                    Thread.sleep(BootEngineTask.FORCED_DELAY);
                    this.backgroundExecutor.execute(() -> JavaFXUtils.runLater(BootEngineTask::openLast));
                } catch (final InterruptedException e) {
                    logger.log(Level.SEVERE, e.getLocalizedMessage(), e);
                    Thread.currentThread().interrupt();
                }
            }).start();
        }

        loadPlugins();

        checkForLatestRelease();
    }

    /**
     * Check for a newer jGnash-Version
     */
    private static void checkForLatestRelease() {
        new Thread(() -> {
            try {
                Thread.sleep(BootEngineTask.FORCED_DELAY * 3L);
                if (Options.checkForUpdatesProperty().get()) {
                    if (Version.isReleaseCurrent() == false) {
                        JavaFXUtils.runLater(() -> StaticUIMethods.displayMessage(ResourceUtils.getString("Message.NewVersion")));
                    }
                    logger.info("Version check performed");
                }
            } catch (final InterruptedException e) {
                logger.log(Level.SEVERE, e.getLocalizedMessage(), e);
                Thread.currentThread().interrupt();
            }
        }).start();
    }

    /**
     * Open working tabs.
     */
    private void addViews() {
        this.backgroundExecutor
            .execute(() -> Platform
                .runLater(() -> this.tabViewPane
                    .addTab(FXMLUtils.load(AccountsViewController.class.getResource("AccountsView.fxml"), this.resources),
                        this.resources.getString("Tab.Accounts"))));

        this.backgroundExecutor
            .execute(() -> Platform
                .runLater(() -> this.tabViewPane
                    .addTab(FXMLUtils.load(RegisterViewController.class.getResource("RegisterView.fxml"), this.resources),
                        this.resources.getString("Tab.Register"))));

        this.backgroundExecutor
            .execute(() -> Platform
                .runLater(() -> this.tabViewPane
                    .addTab(FXMLUtils.load(RecurringViewController.class.getResource("RecurringView.fxml"), this.resources),
                        this.resources.getString("Tab.Reminders"))));

        this.backgroundExecutor
            .execute(() -> Platform
                .runLater(() -> this.tabViewPane
                    .addTab(FXMLUtils.load(BudgetViewController.class.getResource("BudgetView.fxml"), this.resources),
                        this.resources.getString("Tab.Budgeting"))));

        this.backgroundExecutor.execute(() -> Platform.runLater(() -> {
            this.tabViewPane.getSelectionModel().select(this.preferences.getInt(LAST_TAB, 0));

            this.tabListener = (observable, oldValue, newValue) -> {
                if ((newValue != null) && (newValue.intValue() > -1)) { // -1 will occur when all tabs are removed
                    this.preferences.putInt(LAST_TAB, newValue.intValue());
                }
            };

            this.tabViewPane.getSelectionModel().selectedIndexProperty().addListener(new WeakChangeListener<>(this.tabListener));
        }));
    }

    private void removeViews() {
        // Push to the background executor so that a load before current load is finished won't trigger a NPE
        this.backgroundExecutor.execute(() -> JavaFXUtils.runLater(() -> {
            this.tabViewPane.getTabs().clear();
            this.tabListener = null;
        }));
    }

    private static void installHandlers() {

        // Close the file cleanly if it is still open
        getPrimaryStage().addEventHandler(WindowEvent.WINDOW_CLOSE_REQUEST, windowEvent -> {
            PluginFactory.stopPlugins(); // Stop plugins

            if (EngineFactory.getEngine(EngineFactory.DEFAULT) != null) {
                windowEvent.consume(); // consume the event and let the shutdown handler deal with closure
                CloseFileTask.initiateShutdown();
            }
        });
    }

    /**
     * Provides access to the primary {@code Application} stage.
     *
     * @return the primary stage
     */
    public static Stage getPrimaryStage() {
        return getInstance().primaryStage;
    }

    /**
     * Requests focus for the primary {@code Stage}.
     *
     * @see javafx.stage.Stage#requestFocus()
     */
    public static void requestFocus() {
        getPrimaryStage().requestFocus();
    }

    /**
     * Sets the application in a busy state and waits for the provided {@code Task} to complete.
     * The caller is responsible for starting the task.
     *
     * @param task long running {@code Task} to monitor
     */
    public void setBusy(@Nullable final Task<?> task) {
        this.busyPane.setTask(task);
    }

    private static void loadPlugins() {

        // Wrap in an exception handler so a poorly behaving plugin does not prevent application startup
        try {
            PluginFactory.loadPlugins(plugin -> plugin instanceof FxPlugin); // Load only the Fx based plugins
            PluginFactory.startPlugins(Plugin.PluginPlatform.Fx);
        } catch (final Exception e) {
            logger.log(Level.SEVERE, e.getLocalizedMessage(), e);
        }
    }

    /**
     * Message arrived over bus.
     * @param newEvent message event
     */
    @Override
    public void messagePosted(final jgnash.engine.message.Message newEvent) {
        switch (newEvent.getEvent()) {
            case FILE_LOAD_SUCCESS:
                JavaFXUtils.runLater(() -> {
                    this.updateTitle();
                    this.addViews();
                });
                break;
            case FILE_CLOSING:
                JavaFXUtils.runLater(() -> {
                    this.removeViews();
                    this.updateTitle();
                });
                break;
            case FILE_IO_ERROR:
                logger.warning(this.resources.getString("Message.Error.IOError"));
                StaticUIMethods.displayError(this.resources.getString("Message.Error.IOError"));
                break;
            case FILE_LOAD_FAILED:
                logger.warning(this.resources.getString("Message.Error.LoadingFile"));
                StaticUIMethods.displayError(this.resources.getString("Message.Error.LoadingFile"));
                break;
            case FILE_NOT_FOUND:
                logger.warning(this.resources.getString("Message.Error.FileNotFound"));
                break;
            case ACCOUNT_REMOVE_FAILED:
                StaticUIMethods.displayError(this.resources.getString("Message.Error.AccountRemove"));
                break;
            case BACKGROUND_PROCESS_STARTED:
                this.setBusyBackground(true);
                break;
            case BACKGROUND_PROCESS_STOPPED:
                this.setBusyBackground(false);
                break;
            default:
                break;
        }

    }

    private void setBusyBackground(final boolean busy) {
        JavaFXUtils.runLater(() -> {
            if (busy) {
                this.statusBar.progressProperty().set(-1);
            } else {
                this.statusBar.progressProperty().set(0);
            }
        });
    }

    private void updateTitle() {
        this.backgroundExecutor.execute(() -> {
            final Engine engine = EngineFactory.getEngine(EngineFactory.DEFAULT);

            JavaFXUtils.runLater(() -> {
                if (engine != null) {
                    getPrimaryStage().setTitle(TITLE + "  [" + EngineFactory.getActiveDatabase() + ']');
                } else {
                    getPrimaryStage().setTitle(TITLE);
                }
            });
        });
    }

    private class StatusBarLogHandler extends Handler {

        private static final double GRAPHIC_SIZE = 11;

        final Node info;

        final Node warning;

        final Node severe;

        StatusBarLogHandler() {
            this.info = new FontAwesomeLabel(FontAwesomeLabel.FAIcon.INFO, GRAPHIC_SIZE);
            this.warning = new FontAwesomeLabel(FontAwesomeLabel.FAIcon.EXCLAMATION_TRIANGLE, GRAPHIC_SIZE);
            this.severe = new FontAwesomeLabel(FontAwesomeLabel.FAIcon.BUG, GRAPHIC_SIZE, Color.DARKRED);
        }

        @Override
        public void close() {
            // not used
        }

        @Override
        public void flush() {
            // not used
        }

        @Override
        public synchronized void publish(final LogRecord record) {
            if (record.getLevel() == Level.INFO) {
                this.updateStatus(MessageFormat.format(record.getMessage(), record.getParameters()), this.info);
            } else if (record.getLevel() == Level.WARNING) {
                this.updateStatus(MessageFormat.format(record.getMessage(), record.getParameters()), this.warning);
            } else if (record.getLevel() == Level.SEVERE) {
                this.updateStatus(MessageFormat.format(record.getMessage(), record.getParameters()), this.severe);
            }
        }

        private void updateStatus(final String status, final Node glyph) {
            JavaFXUtils.runLater(() -> {
                MainView.this.statusBar.textProperty().set(status);
                MainView.this.statusBar.graphicProperty().set(glyph);
            });
        }
    }
}
