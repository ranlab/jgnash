/*
 * jGnash, a personal finance application
 * Copyright (C) 2001-2019 Craig Cavanaugh
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
package jgnash.uifx.report;

import jgnash.engine.Account;
import jgnash.engine.AccountGroup;
import jgnash.engine.AccountType;
import jgnash.engine.CurrencyNode;
import jgnash.engine.Engine;
import jgnash.engine.EngineFactory;
import jgnash.report.pdf.Report;
import jgnash.report.table.AbstractReportTableModel;
import jgnash.report.table.ColumnHeaderStyle;
import jgnash.report.table.ColumnStyle;
import jgnash.report.table.Row;
import jgnash.resource.util.ResourceUtils;
import jgnash.time.DateUtils;
import jgnash.time.Period;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Abstract Report that groups and sums by {@code AccountGroup} and has a
 * line for a global sum.
 *
 * @author Craig Cavanaugh
 */
public abstract class AbstractSumByTypeReport extends Report {

    boolean runningTotal = true;

    final ArrayList<LocalDate> startDates = new ArrayList<>();

    final ArrayList<LocalDate> endDates = new ArrayList<>();

    private final ArrayList<String> dateLabels = new ArrayList<>();

    protected abstract List<AccountGroup> getAccountGroups();

    /**
     * Returns the reporting period
     *
     * @return returns a Monthly period unless overridden
     */
    private Period getReportPeriod() {
        return Period.MONTHLY;
    }

    ReportModel createReportModel(final LocalDate startDate, final LocalDate endDate,
                                  final boolean hideZeroBalanceAccounts) {

        //logger.info(rb.getString("Message.CollectingReportData"));

        final Engine engine = EngineFactory.getEngine(EngineFactory.DEFAULT);
        Objects.requireNonNull(engine);

        // generate the required date and label arrays
        updateResolution(startDate, endDate);

        final CurrencyNode baseCurrency = engine.getDefaultCurrency();

        List<Account> accounts = new ArrayList<>();

        for (AccountGroup group : getAccountGroups()) {
            accounts.addAll(getAccountList(AccountType.getAccountTypes(group)));
        }

        // remove any account that will report a zero balance for all periods
        if (hideZeroBalanceAccounts) {
            Iterator<Account> i = accounts.iterator();

            while (i.hasNext()) {
                Account account = i.next();
                boolean remove = true;

                if (runningTotal) {
                    for (LocalDate date : startDates) {
                        if (account.getBalance(date).compareTo(BigDecimal.ZERO) != 0) {
                            remove = false;
                            break;
                        }
                    }
                } else {
                    for (int j = 0; j < startDates.size(); j++) {
                        if (account.getBalance(startDates.get(j), endDates.get(j)).compareTo(BigDecimal.ZERO) != 0) {
                            remove = false;
                            break;
                        }
                    }
                }
                if (remove) {
                    i.remove();
                }
            }
        }

        ReportModel model = new ReportModel(baseCurrency);
        model.addAccounts(accounts);

        return model;
    }

    private void updateResolution(final LocalDate startDate, final LocalDate endDate) {

        final DateTimeFormatter dateFormat = DateUtils.getShortDateFormatter();

        //System.out.println(startDate.toString() + ", " + endDate.toString());

        startDates.clear();
        endDates.clear();
        dateLabels.clear();

        LocalDate start = startDate;
        LocalDate end = startDate;

        switch (getReportPeriod()) {
            case YEARLY:
                while (end.isBefore(endDate)) {
                    startDates.add(start);
                    end = end.with(TemporalAdjusters.lastDayOfYear());
                    endDates.add(end);
                    dateLabels.add(String.valueOf(start.getYear()));
                    start = end.plusDays(1);
                }
                break;
            case QUARTERLY:
                int i = DateUtils.getQuarterNumber(start) - 1;
                while (end.isBefore(endDate)) {
                    startDates.add(start);
                    end = DateUtils.getLastDayOfTheQuarter(start);
                    endDates.add(end);
                    dateLabels.add(start.getYear() + "-Q" + (1 + i++ % 4));
                    start = end.plusDays(1);
                }
                break;
            case MONTHLY:   // default is monthly
            default:
                endDates.addAll(DateUtils.getLastDayOfTheMonths(startDate, endDate));
                startDates.addAll(DateUtils.getFirstDayOfTheMonths(startDate, endDate));

                startDates.set(0, startDate);   // force the start date

                if (runningTotal) {
                    for (final LocalDate date : endDates) {
                        dateLabels.add(dateFormat.format(date));
                    }
                } else {
                    for (int j = 0; j < startDates.size(); j++) {
                        dateLabels.add(dateFormat.format(startDates.get(j)) + " - " + dateFormat.format(endDates.get(j)));
                    }
                }

                //System.out.println("startDates: " + startDates.size());
                //System.out.println("endDates: " + endDates.size());

                break;
        }

        assert startDates.size() == endDates.size() && startDates.size() == dateLabels.size();

        // adjust label for global end date
        if (endDates.get(startDates.size() - 1).compareTo(endDate) > 0) {
            endDates.set(endDates.size() - 1, endDate);
        }
    }

    static List<Account> getAccountList(final Set<AccountType> types) {
        final Engine engine = EngineFactory.getEngine(EngineFactory.DEFAULT);
        Objects.requireNonNull(engine);

        return engine.getAccountList().stream().
                filter(a -> types.contains(a.getAccountType())).distinct().sorted().collect(Collectors.toList());
    }

    void setRunningTotal(final boolean runningTotal) {
        this.runningTotal = runningTotal;
    }

    protected class ReportModel extends AbstractReportTableModel {

        private final List<Row<?>> rowList = new ArrayList<>();

        private final CurrencyNode baseCurrency;

        private final ResourceBundle rb = ResourceUtils.getBundle();

        ReportModel(final CurrencyNode currency) {
            this.baseCurrency = currency;
        }

        void addAccounts(final Collection<Account> accounts) {
            accounts.forEach(this::addAccount);
        }

        /**
         * Supports manual addition of a report row
         *
         * @param row the Row to add
         */
        void addRow(final Row<?> row) {
            rowList.add(row);
        }

        void addAccount(final Account account) {
            rowList.add(new AccountRow(account));
        }

        @Override
        public int getRowCount() {
            return rowList.size();
        }

        @Override
        public int getColumnCount() {
            return startDates.size() + 2;
        }

        @Override
        public Object getValueAt(final int rowIndex, final int columnIndex) {
            if (!rowList.isEmpty()) {
                return rowList.get(rowIndex).getValueAt(columnIndex);
            }

            return null;
        }

        @Override
        public CurrencyNode getCurrency() {
            return baseCurrency;
        }

        @Override
        public Class<?> getColumnClass(final int columnIndex) {
            if (columnIndex == 0 || columnIndex == getColumnCount() - 1) { // accounts and group column
                return String.class;
            }

            return BigDecimal.class;
        }

        @Override
        public String getColumnName(final int columnIndex) {
            if (columnIndex == 0) {
                return rb.getString("Column.Account");
            } else if (columnIndex == getColumnCount() - 1) {
                return "Type";
            }

            return dateLabels.get(columnIndex - 1);
        }

        @Override
        public ColumnStyle getColumnStyle(final int columnIndex) {
            if (columnIndex == 0) { // accounts column
                return ColumnStyle.STRING;
            } else if (columnIndex == getColumnCount() - 1) { // group column
                return ColumnStyle.GROUP;
            }
            return ColumnStyle.BALANCE_WITH_SUM_AND_GLOBAL;
        }

        @Override
        public ColumnHeaderStyle getColumnHeaderStyle(final int columnIndex) {
            if (columnIndex == 0) { // accounts column
                return ColumnHeaderStyle.LEFT;
            } else if (columnIndex == getColumnCount() - 1) { // group column
                return ColumnHeaderStyle.CENTER;
            }
            return ColumnHeaderStyle.RIGHT;
        }

        private class AccountRow extends Row<Account> {

            AccountRow(final Account account) {
                super(account);
            }

            @Override
            public Object getValueAt(final int columnIndex) {

                if (columnIndex == 0) { // account column
                    return getValue().getName();
                } else if (columnIndex == getColumnCount() - 1) { // group column
                    return getValue().getAccountType().getAccountGroup().toString();
                } else if (columnIndex > 0 && columnIndex <= startDates.size()) {
                    if (runningTotal) {
                        return getValue().getBalance(endDates.get(columnIndex - 1), getCurrency());
                    }

                    return getValue().getBalance(startDates.get(columnIndex - 1), endDates.get(columnIndex - 1),
                            getCurrency()).negate();
                }

                return null;
            }
        }
    }
}
