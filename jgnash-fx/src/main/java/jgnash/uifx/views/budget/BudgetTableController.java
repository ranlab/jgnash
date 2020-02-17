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
package jgnash.uifx.views.budget;

import static jgnash.uifx.skin.StyleClass.BOLD_LABEL_ID;
import static jgnash.uifx.skin.StyleClass.BOLD_NEGATIVE_LABEL_ID;
import static jgnash.uifx.skin.StyleClass.NORMAL_CELL_ID;
import static jgnash.uifx.skin.StyleClass.NORMAL_NEGATIVE_CELL_ID;
import static jgnash.uifx.skin.StyleClass.TODAY_BOLD_LABEL_ID;
import static jgnash.uifx.skin.StyleClass.TODAY_BOLD_NEGATIVE_LABEL_ID;
import static jgnash.uifx.skin.StyleClass.TODAY_NORMAL_CELL_ID;
import static jgnash.uifx.skin.StyleClass.TODAY_NORMAL_NEGATIVE_CELL_ID;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.prefs.Preferences;
import java.util.stream.Collectors;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.WeakChangeListener;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TableView;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableCell;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTableView;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;
import jgnash.engine.Account;
import jgnash.engine.AccountGroup;
import jgnash.engine.Comparators;
import jgnash.engine.Engine;
import jgnash.engine.EngineFactory;
import jgnash.engine.budget.Budget;
import jgnash.engine.budget.BudgetGoal;
import jgnash.engine.budget.BudgetPeriodDescriptor;
import jgnash.engine.budget.BudgetPeriodResults;
import jgnash.engine.budget.BudgetResultsModel;
import jgnash.engine.message.Message;
import jgnash.engine.message.MessageListener;
import jgnash.engine.message.MessageProperty;
import jgnash.text.NumericFormats;
import jgnash.time.Period;
import jgnash.uifx.control.NullTableViewSelectionModel;
import jgnash.uifx.skin.StyleClass;
import jgnash.uifx.skin.ThemeManager;
import jgnash.uifx.util.FXMLUtils;
import jgnash.uifx.util.JavaFXUtils;
import jgnash.uifx.util.StageUtils;
import jgnash.util.DefaultDaemonThreadFactory;
import jgnash.util.NotNull;

/**
 * Controller for budget tables.
 *
 * @author Craig Cavanaugh
 */
public class BudgetTableController implements MessageListener {

    private static final String RUNNING_TOTALS = "runningTotals";

    private static final String ACCOUNT_COLUMN_WIDTH = "accountColumnWidth";

    private static final int ROW_HEIGHT_MULTIPLIER = 2;

    //TODO: Magic number that needs to be fixed or controlled with css
    private static final double BORDER_MARGIN = 2;

    // allow a selection span of +/- the specified number of years
    private static final int YEAR_MARGIN = 15;

    // Initial column width
    private static final double INITIAL_WIDTH = 55;

    private static final String NOW = "now";

    private final Preferences preferences = Preferences.userNodeForPackage(BudgetTableController.class);

    @FXML
    private CheckBox runningTotalsButton;

    @FXML
    private HBox sparkLinePane;

    @FXML
    private ScrollBar horizontalScrollBar;

    @FXML
    private GridPane gridPane;

    @FXML
    private Spinner<Integer> yearSpinner;

    @FXML
    private ScrollBar verticalScrollBar;

    @FXML
    private TreeTableView<Account> accountTreeView;

    @FXML
    private TableView<Account> periodTable;

    @FXML
    private TableView<Account> accountSummaryTable;

    @FXML
    private TableView<AccountGroup> periodSummaryTable;

    @FXML
    private TableView<AccountGroup> accountGroupPeriodSummaryTable;

    @FXML
    private TableView<AccountGroup> accountTypeTable;

    @FXML
    private ResourceBundle resources;

    private final SimpleObjectProperty<Budget> budget = new SimpleObjectProperty<>();

    private BudgetResultsModel budgetResultsModel;

    /**
     * This list is updated to track the expanded rows of the TreeTableView.
     * This should be the model for all account specific tables
     */
    private final ObservableList<Account> expandedAccountList = FXCollections.observableArrayList();

    /**
     * This list is updated to track the displayed AccountGroups.
     * This should be the model for all account specific tables
     */
    private final ObservableList<AccountGroup> accountGroupList = FXCollections.observableArrayList();

    private final DoubleProperty rowHeight = new SimpleDoubleProperty();

    /**
     * Bind the max and minimum values of every column to this width.
     */
    private final DoubleProperty columnWidth = new SimpleDoubleProperty(INITIAL_WIDTH);

    /**
     * Bind the max and minimum values of every column to this width.
     */
    private final DoubleProperty remainingColumnWidth = new SimpleDoubleProperty(INITIAL_WIDTH);

    /**
     * The is the minimum column width required to display the largest numeric value. Value is cached and only
     * updated with budget or transaction changes
     */
    private double minColumnWidth = INITIAL_WIDTH;

    /**
     * The is the minimum column width required to display the largest numeric summary value.
     */
    private final DoubleProperty minSummaryColumnWidth = new SimpleDoubleProperty(INITIAL_WIDTH);

    /**
     * Current index to be used for scrolling the display.  0 is the first period is displayed to the left
     */
    private int index;

    /**
     * The number of visible columns.
     */
    private final IntegerProperty visibleColumnCount = new SimpleIntegerProperty(1);

    /**
     * The number of periods in the model.
     */
    private final IntegerProperty periodCount = new SimpleIntegerProperty(1);

    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    /**
     * Listens to changes to the width of the period table and optimizes the column widths.
     */
    private ChangeListener<Number> tableWidthChangeListener;

    /**
     * Listens for changes to the font scale
     */
    @SuppressWarnings("FieldCanBeLocal")
    private ChangeListener<Number> fontScaleListener;

    /**
     * Rate limiting executor.
     */
    private ScheduledThreadPoolExecutor rateLimitExecutor;

    private static final int UPDATE_PERIOD = 350; // update period in milliseconds

    /**
     * Used to alter timing for rate limiting the first boot for a better visual effect
     */
    private volatile boolean booted = false;

    private double startDragX;

    private double startDragWidth;

    /**
     * Pseudo divider width is a function of the base text height
     */
    private double dividerWidth;

    @FXML
    private void initialize() {
        this.runningTotalsButton.selectedProperty().setValue(this.preferences.getBoolean(RUNNING_TOTALS, false));

        this.rateLimitExecutor = new ScheduledThreadPoolExecutor(1, new DefaultDaemonThreadFactory("Budget View Rate Limit Executor"),
            new ThreadPoolExecutor.DiscardPolicy());

        this.tableWidthChangeListener = (observable, oldValue, newValue) -> {
            if ((newValue != null) && !oldValue.equals(newValue)) {
                this.optimizeColumnWidths();
            }
        };

        this.handleFontHeightChange();

        this.yearSpinner
            .setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(LocalDate.now().getYear() - YEAR_MARGIN,
                LocalDate.now().getYear() + YEAR_MARGIN, LocalDate.now().getYear(), 1));

        this.accountTreeView.getStylesheets().addAll(StyleClass.HIDE_HORIZONTAL_CSS, StyleClass.HIDE_VERTICAL_CSS);
        this.accountTreeView.setColumnResizePolicy(TreeTableView.CONSTRAINED_RESIZE_POLICY);
        this.accountTreeView.setShowRoot(false);
        this.accountTreeView.setEditable(true);
        this.accountTreeView.fixedCellSizeProperty().bind(this.rowHeight);

        this.accountSummaryTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        this.accountSummaryTable.getStyleClass().addAll(StyleClass.HIDDEN_ROW_FOCUS);
        this.accountSummaryTable.getStylesheets().addAll(StyleClass.HIDE_HORIZONTAL_CSS, StyleClass.HIDE_VERTICAL_CSS);
        this.accountSummaryTable.setItems(this.expandedAccountList);
        this.accountSummaryTable.fixedCellSizeProperty().bind(this.rowHeight);
        this.accountSummaryTable.setSelectionModel(new NullTableViewSelectionModel<>(this.accountSummaryTable));

        this.accountTypeTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        this.accountTypeTable.getStyleClass().addAll(StyleClass.HIDDEN_COLUMN_HEADER, StyleClass.HIDDEN_ROW_FOCUS);
        this.accountTypeTable.getStylesheets().addAll(StyleClass.HIDE_HORIZONTAL_CSS, StyleClass.HIDE_VERTICAL_CSS);
        this.accountTypeTable.setItems(this.accountGroupList);
        this.accountTypeTable.fixedCellSizeProperty().bind(this.rowHeight);
        this.accountTypeTable.prefHeightProperty().bind(this.rowHeight.multiply(Bindings.size(this.accountGroupList)).add(BORDER_MARGIN));
        this.accountTypeTable.setSelectionModel(new NullTableViewSelectionModel<>(this.accountTypeTable));

        // widths need to be bound to the tree view widths for drag/resizing to work
        this.accountTypeTable.minWidthProperty().bind(this.accountTreeView.minWidthProperty());
        this.accountTypeTable.prefWidthProperty().bind(this.accountTreeView.prefWidthProperty());

        this.accountGroupPeriodSummaryTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        this.accountGroupPeriodSummaryTable.getStyleClass().addAll(StyleClass.HIDDEN_COLUMN_HEADER, StyleClass.HIDDEN_ROW_FOCUS);
        this.accountGroupPeriodSummaryTable.getStylesheets().addAll(StyleClass.HIDE_HORIZONTAL_CSS, StyleClass.HIDE_VERTICAL_CSS);
        this.accountGroupPeriodSummaryTable.setItems(this.accountGroupList);
        this.accountGroupPeriodSummaryTable.fixedCellSizeProperty().bind(this.rowHeight);
        this.accountGroupPeriodSummaryTable
            .prefHeightProperty()
                .bind(this.rowHeight.multiply(Bindings.size(this.accountGroupList)).add(BORDER_MARGIN));
        this.accountGroupPeriodSummaryTable.setSelectionModel(new NullTableViewSelectionModel<>(this.accountGroupPeriodSummaryTable));

        this.buildAccountTreeTable();
        this.buildAccountTypeTable();
        this.buildAccountSummaryTable();
        this.buildAccountGroupSummaryTable();

        this.accountSummaryTable.maxWidthProperty().bind(this.minSummaryColumnWidth.multiply(3.0).add(BORDER_MARGIN));
        this.accountGroupPeriodSummaryTable.maxWidthProperty().bind(this.minSummaryColumnWidth.multiply(3.0).add(BORDER_MARGIN));

        this.accountSummaryTable.minWidthProperty().bind(this.minSummaryColumnWidth.multiply(3.0).add(BORDER_MARGIN));
        this.accountGroupPeriodSummaryTable.minWidthProperty().bind(this.minSummaryColumnWidth.multiply(3.0).add(BORDER_MARGIN));

        this.accountTreeView
            .expandedItemCountProperty()
                .addListener((observable, oldValue, newValue) -> JavaFXUtils.runLater(this::updateExpandedAccountList));

        // Calling handleBudgetChange which works for most changes, but can trigger an exception.
        // handleBudgetUpdate rate limits and prevents an exception.
        final ChangeListener<Object> budgetChangeListener = (observable, oldValue, newValue) -> this.handleBudgetUpdate();

        this.budget.addListener(budgetChangeListener);
        this.yearSpinner.valueProperty().addListener(budgetChangeListener);
        this.runningTotalsButton.selectedProperty().addListener(budgetChangeListener);
        this.visibleColumnCount.addListener(budgetChangeListener);

        this.runningTotalsButton
            .selectedProperty()
                .addListener((observable, oldValue, newValue) -> this.preferences.putBoolean(RUNNING_TOTALS, newValue));

        /* Setting the tables as un-managed effectively removes these tables from the GridPane.  The tables are
           redundant if showing the amounts as running balances. */
        this.accountSummaryTable.managedProperty().bind(this.runningTotalsButton.selectedProperty().not());
        this.accountGroupPeriodSummaryTable.managedProperty().bind(this.runningTotalsButton.selectedProperty().not());

        this.horizontalScrollBar.setMin(0);
        this.horizontalScrollBar.maxProperty().bind(this.periodCount.subtract(this.visibleColumnCount));
        this.horizontalScrollBar.setUnitIncrement(1);
        this.horizontalScrollBar.disableProperty().bind(this.periodCount.lessThanOrEqualTo(1));

        // shift the table right and left with the ScrollBar value
        this.horizontalScrollBar
            .valueProperty()
                .addListener((observable, oldValue, newValue) -> {

                    /* must be synchronized to prevent a race condition from multiple events and an out of
                     * bounds exception */
                    synchronized (this) {

                        /* don't try unless columns exist.  This can occur if the UI is not large enough to display
                         * a minimum of one period of information.
                         */
                        if (this.periodTable.getColumns().size() > 0) {
                            final int newIndex = (int) Math.round(newValue.doubleValue());

                            if (newIndex > this.index) {
                                while (newIndex > this.index) {
                                    this.handleShiftRight();
                                }
                            } else if (newIndex < this.index) {
                                while (newIndex < this.index) {
                                    this.handleShiftLeft();
                                }
                            }
                        }
                    }
                });

        // listen for changes in the font scale and update.  Listener needs to be weak to prevent memory leaks
        this.fontScaleListener = (observable, oldValue, newValue) -> this.handleFontHeightChange();
        ThemeManager.fontScaleProperty().addListener(new WeakChangeListener<>(this.fontScaleListener));

        this.accountTreeView.setOnMouseMoved(this::handleMouseMove); // cursor handler
        this.accountTreeView.setOnMouseDragged(this::handleDividerDrag); // drag handler
        this.accountTreeView.setOnMousePressed(this::handleMouseClicked); // drag handler

        this.accountTypeTable.setOnMouseMoved(this::handleMouseMove); // cursor handler
        this.accountTypeTable.setOnMouseDragged(this::handleDividerDrag); // drag handler
        this.accountTypeTable.setOnMousePressed(this::handleMouseClicked); // drag handler

        JavaFXUtils.runLater(() -> this.accountTreeView.setPrefWidth(this.preferences.getDouble(ACCOUNT_COLUMN_WIDTH, INITIAL_WIDTH * 2)));
    }

    /**
     * Determines if the cursor is hovering over the pseudo divider
     *
     * @param xPos x position of the scene
     * @return true if hovering over the divider
     */
    private boolean isOnDivider(final double xPos) {
        final Point2D nodeInScene = this.accountTreeView.localToScene(this.accountTreeView.getLayoutX(), this.accountTreeView.getLayoutY());

        return Math.abs((this.accountTreeView.getWidth() + nodeInScene.getX()) - xPos) < (this.dividerWidth * 0.5);
    }

    private void handleDividerDrag(final MouseEvent event) {
        this.accountTreeView.setPrefWidth(Math.max((this.startDragWidth + event.getSceneX()) - this.startDragX, INITIAL_WIDTH * 2));
        this.preferences.putDouble(ACCOUNT_COLUMN_WIDTH, this.accountTreeView.getWidth());
        event.consume();
    }

    /**
     * Saves information for calculating drag/resize of the account tree column
     *
     * @param event Mouse event
     */
    private void handleMouseClicked(final MouseEvent event) {
        this.startDragX = event.getSceneX();
        this.startDragWidth = this.accountTreeView.getWidth();
        event.consume();
    }

    /**
     * Handles changing the cursor shape when hovering over the pseudo divider
     *
     * @param event Mouse event
     */
    private void handleMouseMove(final MouseEvent event) {
        this.gridPane.getScene().setCursor((event != null) && this.isOnDivider(event.getSceneX()) ? Cursor.H_RESIZE : Cursor.DEFAULT);
    }

    private void rateLimitUpdate(final Runnable runnable) {
        this.rateLimitExecutor.schedule(() -> {
            if (this.rateLimitExecutor.getQueue().size() < 1) { // ignore if we already have one waiting in the queue
                JavaFXUtils.runLater(runnable); // update is assumed to be on the platform thread
            }

            this.booted = true;
        }, this.booted ? UPDATE_PERIOD : 0, TimeUnit.MILLISECONDS);
    }

    @FXML
    private void handleShiftLeft() {
        this.lock.writeLock().lock();

        try {
            // remove the right column
            this.periodTable.getColumns().remove(this.visibleColumnCount.get() - 1);
            this.periodSummaryTable.getColumns().remove(this.visibleColumnCount.get() - 1);

            this.index--;

            // insert a new column to the left
            this.periodTable.getColumns().add(0, this.buildAccountPeriodResultsColumn(this.index));
            this.periodSummaryTable.getColumns().add(0, this.buildAccountPeriodSummaryColumn(this.index));
        } finally {
            this.lock.writeLock().unlock();
        }
    }

    @FXML
    private void handleShiftRight() {
        this.lock.writeLock().lock();

        try {
            // remove leftmost column
            this.periodTable.getColumns().remove(0);
            this.periodSummaryTable.getColumns().remove(0);

            int newColumn = this.index + this.visibleColumnCount.get();

            newColumn = Math.min(newColumn, this.budgetResultsModel.getDescriptorList().size() - 1);

            // add a new column to the right
            this.periodTable.getColumns().add(this.buildAccountPeriodResultsColumn(newColumn));
            this.periodSummaryTable.getColumns().add(this.buildAccountPeriodSummaryColumn(newColumn));

            this.index++;
        } finally {
            this.lock.writeLock().unlock();
        }
    }

    BudgetResultsModel getBudgetResultsModel() {
        return this.budgetResultsModel;
    }

    SimpleObjectProperty<Budget> budgetProperty() {
        return this.budget;
    }

    private void handleFontHeightChange() {
        this.rowHeight.set(ThemeManager.getBaseTextHeight() * ROW_HEIGHT_MULTIPLIER);
        this.dividerWidth = ThemeManager.getBaseTextHeight(); // update divider width
    }

    /**
     * The table view will lazily create the ScrollBars which makes finding them tricky.  We need to check for
     * their existence and try again later if they do not exist.
     * <p>
     * Synchronize binding, otherwise the ScrollBars get a bit confused and do not respond to a scroll wheel
     */
    private synchronized void bindScrollBars() {
        final Optional<ScrollBar> accountScrollBar = JavaFXUtils.findVerticalScrollBar(this.accountTreeView);
        final Optional<ScrollBar> vDataScrollBar = JavaFXUtils.findVerticalScrollBar(this.periodTable);
        final Optional<ScrollBar> accountSumScrollBar = JavaFXUtils.findVerticalScrollBar(this.accountSummaryTable);

        if (vDataScrollBar.isEmpty() || accountScrollBar.isEmpty() || accountSumScrollBar.isEmpty()) {
            Platform.runLater(BudgetTableController.this::bindScrollBars); //re-spawn on the application thread
        } else { // all here, lets bind then now
            this.verticalScrollBar.minProperty().bindBidirectional(accountScrollBar.get().minProperty());
            this.verticalScrollBar.maxProperty().bindBidirectional(accountScrollBar.get().maxProperty());
            this.verticalScrollBar.valueProperty().bindBidirectional(accountScrollBar.get().valueProperty());

            accountScrollBar.get().valueProperty().bindBidirectional(vDataScrollBar.get().valueProperty());
            accountSumScrollBar.get().valueProperty().bindBidirectional(vDataScrollBar.get().valueProperty());
        }
    }

    /**
     * Model must be rebuilt if the year or a budget property is changed.
     * <p>
     * This method is synchronized to limit more than one update attempt at a time.
     */
    private synchronized void handleBudgetChange() {
        this.lock.writeLock().lock();

        try {
            final Engine engine = EngineFactory.getEngine(EngineFactory.DEFAULT);

            if ((this.budget.get() != null) && (engine != null)) {

                // unregister listener from the old model because the model will be replaced
                if (this.budgetResultsModel != null) {
                    this.budgetResultsModel.removeMessageListener(this); // unregister from the old model
                }

                // Build the new results model
                this.budgetResultsModel = new BudgetResultsModel(this.budget.get(), this.yearSpinner.getValue(),
                    engine.getDefaultCurrency(), this.runningTotalsButton.isSelected());

                // model has changed, calculate the minimum column width for the summary columns
                this.minSummaryColumnWidth.set(this.calculateMinSummaryWidthColumnWidth());

                // model has changed, calculate the minimum column width the data model needs
                this.minColumnWidth = this.calculateMinPeriodColumnWidth();

                // register the listener with the new model
                this.budgetResultsModel.addMessageListener(this); // register with the new model

                // update the number of periods the budget model has
                this.periodCount.set(this.budgetResultsModel.getDescriptorList().size());

                // load the model
                this.loadModel();
            } else {
                Platform.runLater(() -> {
                    this.accountTreeView.setRoot(null);
                    this.expandedAccountList.clear();
                    this.accountGroupList.clear();
                });
            }
        } finally {
            this.lock.writeLock().unlock();
        }
    }

    /**
     * Maintains the list of expanded accounts.
     */
    private synchronized void updateExpandedAccountList() {
        final int count = this.accountTreeView.getExpandedItemCount();

        // Create a new list and update the observable list in one shot to minimize visual updates
        final List<Account> accountList = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            accountList.add(this.accountTreeView.getTreeItem(i).getValue());
        }

        this.expandedAccountList.setAll(accountList);
    }

    private void loadModel() {
        this.lock.readLock().lock();

        try {
            this.loadAccountTree();

            this.accountGroupList.setAll(this.budgetResultsModel.getAccountGroupList());

            this.optimizeColumnWidths();

            this.buildPeriodTable();
            this.buildPeriodSummaryTable();

            this.updateExpandedAccountList();

            this.updateSparkLines();

            Platform.runLater(this::bindScrollBars);

            Platform.runLater(this::focusCurrentPeriod);
        } finally {
            this.lock.readLock().unlock();
        }
    }

    private void optimizeColumnWidths() {
        this.lock.writeLock().lock();

        try {
            final double availWidth = this.periodTable.getWidth() - BORDER_MARGIN; // width of the table

            /* calculate the number of visible columns, period columns are 3 columns wide
               the maximum is caped to no more than the number of available periods */
            final int maxVisible = Math
                .min((int) Math.floor(availWidth / (this.minColumnWidth * 3.0)), this.budgetResultsModel.getDescriptorList().size());

            /* update the number of visible columns factoring in the size of the descriptor list */
            this.visibleColumnCount.set((Math.min(this.budgetResultsModel.getDescriptorList().size(), maxVisible)));

            if (this.visibleColumnCount.get() == 0) {
                this.periodTable.placeholderProperty().setValue(new Label(this.resources.getString("Message.Warn.WindowWidth")));

                this.periodSummaryTable.placeholderProperty().setValue(new Label(this.resources.getString("Message.Warn.WindowWidth")));
            }

            final double width = Math.floor(availWidth / Math.min(this.budgetResultsModel.getDescriptorList().size() * 3, maxVisible * 3));

            this.columnWidth.set(width);

            final double remainder = availWidth - (maxVisible * 3.0 * width);

            this.remainingColumnWidth.set(Math.floor(width + (remainder / 3.0)));
        } finally {
            this.lock.writeLock().unlock();
        }
    }

    void focusCurrentPeriod() {
        final LocalDate now = LocalDate.now();

        final List<BudgetPeriodDescriptor> budgetPeriodDescriptorList = this.budgetResultsModel.getDescriptorList();

        for (int i = 0; i < budgetPeriodDescriptorList.size(); i++) {
            final BudgetPeriodDescriptor budgetPeriodDescriptor = budgetPeriodDescriptorList.get(i);

            if (budgetPeriodDescriptor.isBetween(now)) {
                final int index = Math.max(Math.min(i, this.periodCount.subtract(this.visibleColumnCount).intValue()), 0);

                Platform.runLater(() -> this.horizontalScrollBar.setValue(index));
                break;
            }
        }
    }

    private void loadAccountTree() {
        final Engine engine = EngineFactory.getEngine(EngineFactory.DEFAULT);
        Objects.requireNonNull(engine);

        final TreeItem<Account> root = new TreeItem<>(engine.getRootAccount());
        root.setExpanded(true);

        this.accountTreeView.setRoot(root);
        this.loadChildren(root);
    }

    private synchronized void loadChildren(final TreeItem<Account> parentItem) {
        final Account parent = parentItem.getValue();

        parent.getChildren(Comparators.getAccountByCode()).stream().filter(this.budgetResultsModel::includeAccount).forEach(child -> {
            final TreeItem<Account> childItem = new TreeItem<>(child);
            childItem.setExpanded(true);
            parentItem.getChildren().add(childItem);

            if (child.getChildCount() > 0) {
                this.loadChildren(childItem);
            }
        });
    }

    /**
     * Constructs the tree table.
     *
     * @see Stage#showAndWait() for need to push {@code handleEditAccountGoals(account)} to the platform thread
     */
    private void buildAccountTreeTable() {
        // empty column header is needed to match other table columns
        final TreeTableColumn<Account, String> headerColumn = new TreeTableColumn<>("");

        final TreeTableColumn<Account, Account> nameColumn = new TreeTableColumn<>(this.resources.getString("Column.Account"));

        nameColumn.setCellFactory(param -> new AccountTreeTableCell());
        nameColumn.setCellValueFactory(param -> new SimpleObjectProperty<>(param.getValue().getValue()));

        nameColumn.setOnEditStart(event -> {
            final Account account = event.getRowValue().getValue();

            if ((account != null) && !account.isPlaceHolder()) {

                // push to the edit of the platform thread to avoid an IllegalStateException
                // "showAndWait is not allowed during animation or layout processing" exception
                // this may be reached outside the platform thread if a uses is click happy
                if (Platform.isFxApplicationThread()) {
                    this.handleEditAccountGoals(account);
                } else {
                    Platform.runLater(() -> this.handleEditAccountGoals(account));
                }
            }
        });
        nameColumn.setEditable(true);

        headerColumn.getColumns().add(nameColumn);

        this.accountTreeView.getColumns().add(headerColumn);
    }

    private void buildAccountTypeTable() {
        final javafx.scene.control.TableColumn<AccountGroup, String> nameColumn = new javafx.scene.control.TableColumn<>(
            this.resources.getString("Column.Type"));
        nameColumn.setCellValueFactory(param -> new SimpleStringProperty(param.getValue().toString()));

        this.accountTypeTable.getColumns().add(nameColumn);
    }

    private void buildAccountSummaryTable() {
        final javafx.scene.control.TableColumn<Account, BigDecimal> headerColumn = new javafx.scene.control.TableColumn<>(
            this.resources.getString("Title.Summary"));

        final javafx.scene.control.TableColumn<Account, BigDecimal> budgetedColumn = new javafx.scene.control.TableColumn<>(
            this.resources.getString("Column.Budgeted"));

        budgetedColumn.setCellValueFactory(param -> {
            if (param.getValue() != null) {
                return new SimpleObjectProperty<>(this.budgetResultsModel.getResults(param.getValue()).getBudgeted());
            }
            return new SimpleObjectProperty<>(BigDecimal.ZERO);
        });
        budgetedColumn.setCellFactory(param -> new AccountCommodityFormatTableCell());
        this.lockColumnBehavior(budgetedColumn, this.minSummaryColumnWidth);

        headerColumn.getColumns().add(budgetedColumn);

        final javafx.scene.control.TableColumn<Account, BigDecimal> actualColumn = new javafx.scene.control.TableColumn<>(
            this.resources.getString("Column.Actual"));
        actualColumn.setCellValueFactory(param -> {
            if (param.getValue() != null) {
                return new SimpleObjectProperty<>(this.budgetResultsModel.getResults(param.getValue()).getChange());
            }
            return new SimpleObjectProperty<>(BigDecimal.ZERO);
        });
        actualColumn.setCellFactory(param -> new AccountCommodityFormatTableCell());
        this.lockColumnBehavior(actualColumn, this.minSummaryColumnWidth);

        headerColumn.getColumns().add(actualColumn);

        final javafx.scene.control.TableColumn<Account, BigDecimal> remainingColumn = new javafx.scene.control.TableColumn<>(
            this.resources.getString("Column.Remaining"));

        remainingColumn.setCellValueFactory(param -> {
            if (param.getValue() != null) {
                return new SimpleObjectProperty<>(this.budgetResultsModel.getResults(param.getValue()).getRemaining());
            }
            return new SimpleObjectProperty<>(BigDecimal.ZERO);
        });
        remainingColumn.setCellFactory(param -> new AccountCommodityFormatTableCell());
        this.lockColumnBehavior(remainingColumn, this.minSummaryColumnWidth);

        headerColumn.getColumns().add(remainingColumn);
        headerColumn.resizableProperty().set(false);

        this.accountSummaryTable.getColumns().add(headerColumn);
    }

    private javafx.scene.control.TableColumn<Account, BigDecimal> buildAccountPeriodResultsColumn(final int index) {

        final BudgetPeriodDescriptor descriptor = this.budgetResultsModel.getDescriptorList().get(index);

        // determine if the column is to be highlighted if the period is not yearly
        final Boolean highlight = (descriptor.isBetween(LocalDate.now()) ? Boolean.TRUE : Boolean.FALSE)
            && (this.budget.get().getBudgetPeriod() != Period.YEARLY);

        final javafx.scene.control.TableColumn<Account, BigDecimal> headerColumn = new javafx.scene.control.TableColumn<>(
            descriptor.getPeriodDescription());

        final javafx.scene.control.TableColumn<Account, BigDecimal> budgetedColumn = new javafx.scene.control.TableColumn<>(
            this.resources.getString("Column.Budgeted"));

        budgetedColumn.getProperties().put(NOW, highlight);
        budgetedColumn.setCellValueFactory(param -> {
            if (param.getValue() != null) {
                return new SimpleObjectProperty<>(this.budgetResultsModel.getResults(descriptor, param.getValue()).getBudgeted());
            }
            return new SimpleObjectProperty<>(BigDecimal.ZERO);
        });
        budgetedColumn.setCellFactory(param -> new AccountCommodityFormatTableCell());
        this.lockColumnBehavior(budgetedColumn, this.columnWidth);

        headerColumn.getColumns().add(budgetedColumn);

        final javafx.scene.control.TableColumn<Account, BigDecimal> actualColumn = new javafx.scene.control.TableColumn<>(
            this.resources.getString("Column.Actual"));

        actualColumn.getProperties().put(NOW, highlight);
        actualColumn.setCellValueFactory(param -> {
            if (param.getValue() != null) {
                return new SimpleObjectProperty<>(this.budgetResultsModel.getResults(descriptor, param.getValue()).getChange());
            }
            return new SimpleObjectProperty<>(BigDecimal.ZERO);
        });
        actualColumn.setCellFactory(param -> new AccountCommodityFormatTableCell());
        this.lockColumnBehavior(actualColumn, this.columnWidth);

        headerColumn.getColumns().add(actualColumn);

        final javafx.scene.control.TableColumn<Account, BigDecimal> remainingColumn = new javafx.scene.control.TableColumn<>(
            this.resources.getString("Column.Remaining"));

        remainingColumn.getProperties().put(NOW, highlight);
        remainingColumn.setCellValueFactory(param -> {
            if (param.getValue() != null) {
                return new SimpleObjectProperty<>(this.budgetResultsModel.getResults(descriptor, param.getValue()).getRemaining());
            }
            return new SimpleObjectProperty<>(BigDecimal.ZERO);
        });
        remainingColumn.setCellFactory(param -> new AccountCommodityFormatTableCell());
        this.lockColumnBehavior(remainingColumn, this.remainingColumnWidth);

        headerColumn.getColumns().add(remainingColumn);

        headerColumn.resizableProperty().set(false);

        return headerColumn;
    }

    /**
     * The period table must be rebuilt because of JavaFx issues.
     */
    private void buildPeriodTable() {
        // remove the old listener so it does not leak
        this.periodTable.widthProperty().removeListener(this.tableWidthChangeListener);

        // recreate the table and load the new one into the grid pane
        final int row = GridPane.getRowIndex(this.periodTable);
        final int column = GridPane.getColumnIndex(this.periodTable);
        this.gridPane.getChildren().remove(this.periodTable);

        this.periodTable = new TableView<>();
        GridPane.setConstraints(this.periodTable, column, row);
        this.gridPane.getChildren().add(this.periodTable);

        this.periodTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        this.periodTable.getStyleClass().addAll(StyleClass.HIDDEN_ROW_FOCUS);
        this.periodTable.getStylesheets().addAll(StyleClass.HIDE_HORIZONTAL_CSS, StyleClass.HIDE_VERTICAL_CSS);
        this.periodTable.fixedCellSizeProperty().bind(this.rowHeight);
        this.periodTable.setSelectionModel(new NullTableViewSelectionModel<>(this.periodTable));

        // index exceeds allowed value because the user reduced the period count, reset to the maximum allowed value
        if (this.index > (this.budgetResultsModel.getDescriptorList().size() - this.visibleColumnCount.get())) {
            this.index = this.budgetResultsModel.getDescriptorList().size() - this.visibleColumnCount.get();
        }

        final int periodCount = Math.min(this.visibleColumnCount.get(), this.budgetResultsModel.getDescriptorList().size());

        for (int i = this.index; i < (this.index + periodCount); i++) {
            this.periodTable.getColumns().add(this.buildAccountPeriodResultsColumn(i));
        }

        this.periodTable.setItems(this.expandedAccountList);
        this.periodTable.widthProperty().addListener(this.tableWidthChangeListener);

        this.periodTable.setOnMouseMoved(this::handleMouseMove); // cursor
        this.periodTable.setOnMouseDragged(this::handleDividerDrag); // drag
        this.periodTable.setOnMousePressed(this::handleMouseClicked); // drag
    }

    private javafx.scene.control.TableColumn<AccountGroup, BigDecimal> buildAccountPeriodSummaryColumn(final int index) {
        final BudgetPeriodDescriptor descriptor = this.budgetResultsModel.getDescriptorList().get(index);

        // determine if the column is to be highlighted if the period is not yearly
        final Boolean highlight = (descriptor.isBetween(LocalDate.now()) ? Boolean.TRUE : Boolean.FALSE)
            && (this.budget.get().getBudgetPeriod() != Period.YEARLY);

        final javafx.scene.control.TableColumn<AccountGroup, BigDecimal> headerColumn = new javafx.scene.control.TableColumn<>(
            descriptor.getPeriodDescription());

        final javafx.scene.control.TableColumn<AccountGroup, BigDecimal> budgetedColumn = new javafx.scene.control.TableColumn<>(
            this.resources.getString("Column.Budgeted"));

        budgetedColumn.getProperties().put(NOW, highlight);
        budgetedColumn.setCellValueFactory(param -> {
            if (param.getValue() != null) {
                return new SimpleObjectProperty<>(this.budgetResultsModel.getResults(descriptor, param.getValue()).getBudgeted());
            }
            return new SimpleObjectProperty<>(BigDecimal.ZERO);
        });
        budgetedColumn.setCellFactory(param -> new AccountGroupTableCell());
        this.lockColumnBehavior(budgetedColumn, this.columnWidth);

        headerColumn.getColumns().add(budgetedColumn);

        final javafx.scene.control.TableColumn<AccountGroup, BigDecimal> actualColumn = new javafx.scene.control.TableColumn<>(
            this.resources.getString("Column.Actual"));

        actualColumn.getProperties().put(NOW, highlight);
        actualColumn.setCellValueFactory(param -> {
            if (param.getValue() != null) {
                return new SimpleObjectProperty<>(this.budgetResultsModel.getResults(descriptor, param.getValue()).getChange());
            }
            return new SimpleObjectProperty<>(BigDecimal.ZERO);
        });
        actualColumn.setCellFactory(param -> new AccountGroupTableCell());
        this.lockColumnBehavior(actualColumn, this.columnWidth);

        headerColumn.getColumns().add(actualColumn);

        final javafx.scene.control.TableColumn<AccountGroup, BigDecimal> remainingColumn = new javafx.scene.control.TableColumn<>(
            this.resources.getString("Column.Remaining"));

        remainingColumn.getProperties().put(NOW, highlight);
        remainingColumn.setCellValueFactory(param -> {
            if (param.getValue() != null) {
                return new SimpleObjectProperty<>(this.budgetResultsModel.getResults(descriptor, param.getValue()).getRemaining());
            }
            return new SimpleObjectProperty<>(BigDecimal.ZERO);
        });
        remainingColumn.setCellFactory(param -> new AccountGroupTableCell());

        // the max width is not bound to allow last column to grow and fill any voids
        this.lockColumnBehavior(remainingColumn, this.remainingColumnWidth);

        headerColumn.getColumns().add(remainingColumn);

        return headerColumn;
    }

    private void lockColumnBehavior(final javafx.scene.control.TableColumn<?, ?> column, final DoubleProperty columnWidth) {
        column.minWidthProperty().bind(columnWidth);
        column.maxWidthProperty().bind(columnWidth);
        column.setSortable(false);
        column.resizableProperty().set(false);
        column.setReorderable(false);
    }

    /**
     * The period summary table must be rebuilt because of JavaFx issues.
     */
    private void buildPeriodSummaryTable() {
        // recreate the table and load the new one into the grid pane
        final int row = GridPane.getRowIndex(this.periodSummaryTable);
        final int column = GridPane.getColumnIndex(this.periodSummaryTable);

        this.gridPane.getChildren().remove(this.periodSummaryTable);
        this.periodSummaryTable = new TableView<>();

        GridPane.setConstraints(this.periodSummaryTable, column, row);
        this.gridPane.getChildren().add(this.periodSummaryTable);

        this.periodSummaryTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        this.periodSummaryTable.getStyleClass().add(StyleClass.HIDDEN_COLUMN_HEADER);
        this.periodSummaryTable.getStyleClass().addAll(StyleClass.HIDDEN_ROW_FOCUS);
        this.periodSummaryTable.getStylesheets().addAll(StyleClass.HIDE_HORIZONTAL_CSS, StyleClass.HIDE_VERTICAL_CSS);
        this.periodSummaryTable.fixedCellSizeProperty().bind(this.rowHeight);
        this.periodSummaryTable.prefHeightProperty().bind(this.rowHeight.multiply(Bindings.size(this.accountGroupList)).add(BORDER_MARGIN));
        this.periodSummaryTable.setSelectionModel(new NullTableViewSelectionModel<>(this.periodSummaryTable));

        final int periodCount = Math.min(this.visibleColumnCount.get(), this.budgetResultsModel.getDescriptorList().size());

        for (int i = this.index; i < (this.index + periodCount); i++) {
            this.periodSummaryTable.getColumns().add(this.buildAccountPeriodSummaryColumn(i));
        }

        this.periodSummaryTable.setItems(this.accountGroupList);

        this.periodSummaryTable.setOnMouseMoved(this::handleMouseMove); // cursor
        this.periodSummaryTable.setOnMouseDragged(this::handleDividerDrag); // drag
        this.periodSummaryTable.setOnMousePressed(this::handleMouseClicked); // drag
    }

    private void buildAccountGroupSummaryTable() {
        final javafx.scene.control.TableColumn<AccountGroup, BigDecimal> headerColumn = new javafx.scene.control.TableColumn<>(
            this.resources.getString("Title.Summary"));

        final javafx.scene.control.TableColumn<AccountGroup, BigDecimal> budgetedColumn = new javafx.scene.control.TableColumn<>(
            this.resources.getString("Column.Budgeted"));

        budgetedColumn.setCellValueFactory(param -> {
            if (param.getValue() != null) {
                return new SimpleObjectProperty<>(this.budgetResultsModel.getResults(param.getValue()).getBudgeted());
            }
            return new SimpleObjectProperty<>(BigDecimal.ZERO);
        });
        budgetedColumn.setCellFactory(param -> new AccountGroupTableCell());
        budgetedColumn.minWidthProperty().bind(this.minSummaryColumnWidth);
        budgetedColumn.maxWidthProperty().bind(this.minSummaryColumnWidth);
        budgetedColumn.setSortable(false);
        budgetedColumn.resizableProperty().set(false);

        headerColumn.getColumns().add(budgetedColumn);

        final javafx.scene.control.TableColumn<AccountGroup, BigDecimal> actualColumn = new javafx.scene.control.TableColumn<>(
            this.resources.getString("Column.Actual"));

        actualColumn.setCellValueFactory(param -> {
            if (param.getValue() != null) {
                return new SimpleObjectProperty<>(this.budgetResultsModel.getResults(param.getValue()).getChange());
            }
            return new SimpleObjectProperty<>(BigDecimal.ZERO);
        });
        actualColumn.setCellFactory(param -> new AccountGroupTableCell());
        actualColumn.minWidthProperty().bind(this.minSummaryColumnWidth);
        actualColumn.maxWidthProperty().bind(this.minSummaryColumnWidth);
        actualColumn.setSortable(false);
        actualColumn.resizableProperty().set(false);

        headerColumn.getColumns().add(actualColumn);

        final javafx.scene.control.TableColumn<AccountGroup, BigDecimal> remainingColumn = new javafx.scene.control.TableColumn<>(
            this.resources.getString("Column.Remaining"));

        remainingColumn.setCellValueFactory(param -> {
            if (param.getValue() != null) {
                return new SimpleObjectProperty<>(this.budgetResultsModel.getResults(param.getValue()).getRemaining());
            }
            return new SimpleObjectProperty<>(BigDecimal.ZERO);
        });
        remainingColumn.setCellFactory(param -> new AccountGroupTableCell());
        remainingColumn.minWidthProperty().bind(this.minSummaryColumnWidth);
        remainingColumn.maxWidthProperty().bind(this.minSummaryColumnWidth);
        remainingColumn.setSortable(false);
        remainingColumn.resizableProperty().set(false);

        headerColumn.getColumns().add(remainingColumn);

        this.accountGroupPeriodSummaryTable.getColumns().add(headerColumn);
    }

    private double calculateMinColumnWidth(final BudgetPeriodResults budgetPeriodResults) {
        double max = 0;
        double min = 0;

        max = Math.max(max, budgetPeriodResults.getBudgeted().doubleValue());
        max = Math.max(max, budgetPeriodResults.getChange().doubleValue());
        max = Math.max(max, budgetPeriodResults.getRemaining().doubleValue());

        min = Math.min(min, budgetPeriodResults.getBudgeted().doubleValue());
        min = Math.min(min, budgetPeriodResults.getChange().doubleValue());
        min = Math.min(min, budgetPeriodResults.getRemaining().doubleValue());

        return Math
            .max(
                JavaFXUtils
                    .getDisplayedTextWidth(
                        NumericFormats.getFullCommodityFormat(this.budgetResultsModel.getBaseCurrency()).format(max) + BORDER_MARGIN,
                        null),
                JavaFXUtils
                    .getDisplayedTextWidth(
                        NumericFormats.getFullCommodityFormat(this.budgetResultsModel.getBaseCurrency()).format(min) + BORDER_MARGIN,
                        null));
    }

    private double calculateMinColumnWidth(final BudgetPeriodDescriptor descriptor, final Account account) {
        return this.calculateMinColumnWidth(this.budgetResultsModel.getResults(descriptor, account));
    }

    private double calculateMinColumnWidth(final Account account) {
        return this.calculateMinColumnWidth(this.budgetResultsModel.getResults(account));
    }

    private double calculateMinColumnWidth(final BudgetPeriodDescriptor descriptor) {
        double max = 0;
        double min = 0;

        for (final AccountGroup accountGroup : this.accountGroupList) {
            final BudgetPeriodResults budgetPeriodResults = this.budgetResultsModel.getResults(descriptor, accountGroup);
            max = Math.max(max, budgetPeriodResults.getBudgeted().doubleValue());
            max = Math.max(max, budgetPeriodResults.getChange().doubleValue());
            max = Math.max(max, budgetPeriodResults.getRemaining().doubleValue());

            min = Math.min(min, budgetPeriodResults.getBudgeted().doubleValue());
            min = Math.min(min, budgetPeriodResults.getChange().doubleValue());
            min = Math.min(min, budgetPeriodResults.getRemaining().doubleValue());
        }

        return Math
            .max(
                JavaFXUtils
                    .getDisplayedTextWidth(
                        NumericFormats.getFullCommodityFormat(this.budgetResultsModel.getBaseCurrency()).format(max) + BORDER_MARGIN,
                        null),
                JavaFXUtils
                    .getDisplayedTextWidth(
                        NumericFormats.getFullCommodityFormat(this.budgetResultsModel.getBaseCurrency()).format(min) + BORDER_MARGIN,
                        null));
    }

    private double calculateMinPeriodColumnWidth() {
        double max = 0;

        for (final BudgetPeriodDescriptor descriptor : this.budgetResultsModel.getDescriptorList()) {
            for (final Account account : this.expandedAccountList) {
                max = Math.max(max, this.calculateMinColumnWidth(descriptor, account));
            }
        }

        max = Math.max(max, JavaFXUtils.getDisplayedTextWidth(this.resources.getString("Column.Budgeted") + BORDER_MARGIN, null));
        max = Math.max(max, JavaFXUtils.getDisplayedTextWidth(this.resources.getString("Column.Actual") + BORDER_MARGIN, null));
        max = Math.max(max, JavaFXUtils.getDisplayedTextWidth(this.resources.getString("Column.Remaining") + BORDER_MARGIN, null));

        return Math.ceil(max);
    }

    private double calculateMinSummaryWidthColumnWidth() {
        double max = 0;

        for (final BudgetPeriodDescriptor descriptor : this.budgetResultsModel.getDescriptorList()) {
            max = Math.max(max, this.calculateMinColumnWidth(descriptor));
        }

        for (final Account account : this.expandedAccountList) {
            max = Math.max(max, this.calculateMinColumnWidth(account));
        }

        max = Math.max(max, JavaFXUtils.getDisplayedTextWidth(this.resources.getString("Column.Budgeted") + BORDER_MARGIN, null));
        max = Math.max(max, JavaFXUtils.getDisplayedTextWidth(this.resources.getString("Column.Actual") + BORDER_MARGIN, null));
        max = Math.max(max, JavaFXUtils.getDisplayedTextWidth(this.resources.getString("Column.Remaining") + BORDER_MARGIN, null));

        return Math.ceil(max);
    }

    private void handleBudgetUpdate() {
        this.rateLimitUpdate(BudgetTableController.this::handleBudgetChange);
    }

    private void handleTransactionUpdate() {
        this.rateLimitUpdate(() -> {
            synchronized (this) {
                this.periodTable.refresh();
                this.periodSummaryTable.refresh();
            }
            this.accountSummaryTable.refresh();
            this.accountGroupPeriodSummaryTable.refresh();

            this.optimizeColumnWidths();

            this.updateSparkLines();
        });
    }

    private void updateSparkLines() {
        this.sparkLinePane.getChildren().clear();

        for (final AccountGroup group : this.accountGroupList) {
            final List<BigDecimal> remaining = this.budgetResultsModel
                .getDescriptorList()
                    .parallelStream()
                    .map(descriptor -> this.budgetResultsModel.getResults(descriptor, group).getRemaining())
                    .collect(Collectors.toList());

            final HBox hBox = new HBox(new Label(group.toString()), new BudgetSparkLine(remaining));
            hBox.setAlignment(Pos.CENTER_LEFT);

            this.sparkLinePane.getChildren().add(hBox);
        }
    }

    private void handleEditAccountGoals(@NotNull final Account account) {
        Objects.requireNonNull(account);

        final FXMLUtils.Pair<BudgetGoalsDialogController> pair = FXMLUtils
            .load(BudgetGoalsDialogController.class.getResource("BudgetGoalsDialog.fxml"),
                this.resources.getString("Title.BudgetGoal") + " - " + account.getName());

        pair.getController().startMonthProperty().set(this.budget.get().getStartMonth());
        pair.getController().accountProperty().set(account);
        pair.getController().workingYearProperty().set(this.yearSpinner.getValue());

        try {
            final BudgetGoal oldGoal = (BudgetGoal) this.budgetProperty().get().getBudgetGoal(account).clone();
            pair.getController().budgetGoalProperty().set(oldGoal);
        } catch (final CloneNotSupportedException e) {
            Logger.getLogger(BudgetTableController.class.getName()).log(Level.SEVERE, e.getLocalizedMessage(), e);
        }

        StageUtils.addBoundsListener(pair.getStage(), BudgetGoalsDialogController.class);

        pair.getStage().showAndWait();

        final Optional<BudgetGoal> optional = pair.getController().getResult();

        optional.ifPresent(budgetGoal -> {
            final Engine engine = EngineFactory.getEngine(EngineFactory.DEFAULT);
            Objects.requireNonNull(engine);

            engine.updateBudgetGoals(this.budget.get(), account, budgetGoal);
        });
    }

    @Override
    public void messagePosted(final Message message) {
        switch (message.getEvent()) {
            case FILE_CLOSING:
                this.budgetResultsModel.removeMessageListener(this);
                this.budgetProperty().set(null);
                break;
            case BUDGET_REMOVE:
                if (this.budget.get().equals(message.getObject(MessageProperty.BUDGET))) {
                    this.budget.set(null);
                    this.budgetResultsModel.removeMessageListener(this);
                }
                break;
            case ACCOUNT_ADD:
            case ACCOUNT_MODIFY:
            case ACCOUNT_REMOVE:
            case BUDGET_UPDATE:
            case BUDGET_GOAL_UPDATE:
                if (this.budget.get().equals(message.getObject(MessageProperty.BUDGET))) {
                    this.handleBudgetUpdate();
                }
                break;
            case TRANSACTION_ADD:
            case TRANSACTION_REMOVE:
                this.handleTransactionUpdate();
                break;
            default:
                break;
        }
    }

    private class AccountCommodityFormatTableCell extends javafx.scene.control.TableCell<Account, BigDecimal> {

        AccountCommodityFormatTableCell() {
            this.setStyle("-fx-alignment: center-right;"); // Right align
        }

        @Override
        protected void updateItem(final BigDecimal amount, final boolean empty) {
            super.updateItem(amount, empty); // required

            this.setId(NORMAL_CELL_ID); // reset, cell is reused

            final boolean now = Boolean.TRUE == this.getTableColumn().getProperties().getOrDefault(NOW, Boolean.FALSE);

            if (!empty && (amount != null) && (this.getTableRow() != null)) {
                final Account account = BudgetTableController.this.expandedAccountList.get(this.getTableRow().getIndex());
                final NumberFormat format = NumericFormats.getFullCommodityFormat(account.getCurrencyNode());

                this.setText(format.format(amount));

                if (account.isPlaceHolder()) {
                    if (amount.signum() < 0) {
                        this.setId(now ? TODAY_BOLD_NEGATIVE_LABEL_ID : BOLD_NEGATIVE_LABEL_ID);
                    } else {
                        this.setId(now ? TODAY_BOLD_LABEL_ID : BOLD_LABEL_ID);
                    }
                } else {
                    if (amount.signum() < 0) {
                        this.setId(now ? TODAY_NORMAL_NEGATIVE_CELL_ID : NORMAL_NEGATIVE_CELL_ID);
                    } else {
                        this.setId(now ? TODAY_NORMAL_CELL_ID : NORMAL_CELL_ID);
                    }
                }
            } else {
                this.setText(null);
            }
        }
    }

    private class AccountGroupTableCell extends javafx.scene.control.TableCell<AccountGroup, BigDecimal> {

        private final NumberFormat format;

        AccountGroupTableCell() {
            this.setStyle("-fx-alignment: center-right;"); // Right align
            this.format = NumericFormats.getFullCommodityFormat(BudgetTableController.this.budgetResultsModel.getBaseCurrency());
        }

        @Override
        protected void updateItem(final BigDecimal amount, final boolean empty) {
            super.updateItem(amount, empty); // required

            this.setId(NORMAL_CELL_ID); // reset, cell is reused

            final boolean now = Boolean.TRUE == this.getTableColumn().getProperties().getOrDefault(NOW, Boolean.FALSE);

            if (!empty && (amount != null) && (this.getTableRow() != null)) {
                this.setText(this.format.format(amount));

                if (amount.signum() < 0) {
                    this.setId(now ? TODAY_NORMAL_NEGATIVE_CELL_ID : NORMAL_NEGATIVE_CELL_ID);
                } else {
                    this.setId(now ? TODAY_NORMAL_CELL_ID : NORMAL_CELL_ID);
                }
            } else {
                this.setText(null);
            }
        }
    }

    private static class AccountTreeTableCell extends TreeTableCell<Account, Account> {

        @Override
        protected void updateItem(final Account account, final boolean empty) {
            super.updateItem(account, empty); // required

            this.setId(NORMAL_CELL_ID); // reset, cell is reused

            if (!empty && (account != null) && (this.getTreeTableRow() != null)) {
                this.setText(account.getName());

                if (account.isPlaceHolder()) {
                    this.setId(BOLD_LABEL_ID);
                } else {
                    this.setId(NORMAL_CELL_ID);
                }
            } else {
                this.setText(null);
            }
        }
    }
}
