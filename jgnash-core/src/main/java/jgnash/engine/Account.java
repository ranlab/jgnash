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
 *  You should have received account copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package jgnash.engine;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.ElementCollection;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.FetchType;
import javax.persistence.JoinColumn;
import javax.persistence.JoinTable;
import javax.persistence.ManyToMany;
import javax.persistence.ManyToOne;
import javax.persistence.OneToMany;
import javax.persistence.OneToOne;
import javax.persistence.OrderBy;
import javax.persistence.PostLoad;
import javax.persistence.Transient;

import jgnash.time.DateUtils;
import jgnash.util.NotNull;
import jgnash.util.Nullable;

/**
 * Account object.  The {@code Account} object is mutable.  Changes should be made using the {@code Engine} to
 * ensure correct state and persistence.
 *
 * @author Craig Cavanaugh
 * @author Jeff Prickett prickett@users.sourceforge.net
 */
@Entity
public class Account extends StoredObject implements Comparable<Account> {

    static final int MAX_ATTRIBUTE_LENGTH = 8192;

    /**
     * Attribute key for the last attempted reconciliation date.
     */
    public static final String RECONCILE_LAST_ATTEMPT_DATE = "Reconcile.LastAttemptDate";

    /**
     * Attribute key for the last successful reconciliation date.
     */
    public static final String RECONCILE_LAST_SUCCESS_DATE = "Reconcile.LastSuccessDate";

    /**
     * Attribute key for the last reconciliation statement date.
     */
    public static final String RECONCILE_LAST_STATEMENT_DATE = "Reconcile.LastStatementDate";

    /**
     * Attribute key for the last reconciliation opening balance.
     */
    public static final String RECONCILE_LAST_OPENING_BALANCE = "Reconcile.LastOpeningBalance";

    /**
     * Attribute key for the last reconciliation closing balance.
     */
    public static final String RECONCILE_LAST_CLOSING_BALANCE = "Reconcile.LastClosingBalance";

    private static final Pattern numberPattern = Pattern.compile("\\d+");

    private static final Logger logger = Logger.getLogger(Account.class.getName());

    /**
     * String delimiter for reported account structure.
     */
    private static String accountSeparator = ":";

    @ManyToOne
    Account parentAccount;

    /**
     * List of transactions for this account.
     */
    @JoinTable
    @OrderBy("date, number, timestamp")
    @ManyToMany(cascade = { CascadeType.ALL }, fetch = FetchType.EAGER)
    final Set<Transaction> transactions = new HashSet<>();

    /**
     * List of securities if this is an investment account.
     */
    @JoinColumn()
    @OrderBy("symbol")
    @ManyToMany(cascade = { CascadeType.REFRESH, CascadeType.PERSIST, CascadeType.MERGE }, fetch = FetchType.EAGER)
    private final Set<SecurityNode> securities = new HashSet<>();

    @Enumerated(EnumType.STRING)
    private AccountType accountType;

    private boolean placeHolder = false;

    private boolean locked = false;

    private boolean visible = true;

    private boolean excludedFromBudget = false;

    private String name = "";

    private String description = "";

    @Column(columnDefinition = "VARCHAR(8192)")
    private String notes = "";

    /**
     * CurrencyNode for this account.
     */
    @ManyToOne
    private CurrencyNode currencyNode;

    /**
     * Sorted list of child accounts.
     */
    @OrderBy("name")
    @OneToMany(cascade = { CascadeType.ALL }, fetch = FetchType.EAGER)
    private final Set<Account> children = new HashSet<>();

    /**
     * Cached list of sorted transactions that is not persisted. This prevents concurrency issues when using a JPA backend
     */
    @Transient
    private transient List<Transaction> cachedSortedTransactionList;

    /**
     * Cached list of sorted accounts this is not persisted.  This prevents concurrency issues when using a JPA backend
     */
    @Transient
    private transient List<Account> cachedSortedChildren;

    /**
     * Balance of the account.
     *
     * Cached balances cannot be persisted to do nature of JPA
     */
    @Transient
    private transient BigDecimal accountBalance;

    /**
     * Reconciled balance of the account.
     *
     * Cached balances cannot be persisted to do nature of JPA
     */
    @Transient
    private transient BigDecimal reconciledBalance;

    /**
     * User definable account number.
     */
    private String accountNumber = "";

    /**
     * User definable bank id. Useful for OFX import
     */
    private String bankId;

    /**
     * User definable account code.  This will control sort order
     */
    @Column(nullable = false, columnDefinition = "int default 0")
    private int accountCode;

    @OneToOne(orphanRemoval = true, cascade = { CascadeType.ALL })
    private AmortizeObject amortizeObject;

    /**
     * User definable attributes.
     */
    @ElementCollection
    @Column(columnDefinition = "varchar(8192)")
    private final Map<String, String> attributes = new HashMap<>(); // maps from attribute name to value

    private transient ReadWriteLock transactionLock;

    private transient ReadWriteLock childLock;

    private transient ReadWriteLock securitiesLock;

    private transient ReadWriteLock attributesLock;

    private transient AccountProxy proxy;

    /**
     * No argument public constructor for reflection purposes.
     *
     * <b>Do not use to create account new instance</b>
     */
    public Account() {
        this.transactionLock = new ReentrantReadWriteLock(true);
        this.childLock = new ReentrantReadWriteLock(true);
        this.securitiesLock = new ReentrantReadWriteLock(true);
        this.attributesLock = new ReentrantReadWriteLock(true);

        // CopyOnWrite is used as an alternative to defensive copies
        this.cachedSortedChildren = new ArrayList<>();
    }

    public Account(@NotNull final AccountType type, @NotNull final CurrencyNode node) {
        this();

        Objects.requireNonNull(type);
        Objects.requireNonNull(node);

        this.setAccountType(type);
        this.setCurrencyNode(node);
    }

    private static String getAccountSeparator() {
        return accountSeparator;
    }

    static void setAccountSeparator(final String separator) {
        accountSeparator = separator;
    }

    ReadWriteLock getTransactionLock() {
        return this.transactionLock;
    }

    private AccountProxy getProxy() {
        if (this.proxy == null) {
            this.proxy = this.getAccountType().getProxy(this);
        }
        return this.proxy;
    }

    /**
     * Clear cached account balances so they will be recalculated.
     */
    void clearCachedBalances() {
        this.accountBalance = null;
        this.reconciledBalance = null;
    }

    /**
     * Adds account transaction in chronological order.
     *
     * @param tran the {@code Transaction} to be added
     * @return <tt>true</tt> the transaction was added successful <tt>false</tt> the transaction was already attached
     * to this account
     */
    boolean addTransaction(final Transaction tran) {
        if (this.placeHolder) {
            java.lang.StringBuffer sb = new java.lang.StringBuffer();
            tran.getAccounts().stream().forEach(acc -> sb.append(acc.getName() + " "));
            logger.severe(java.lang.String.format("%s (%s)", "Tried to add transaction to a place holder account", sb.toString()));
            return false;
        }

        this.transactionLock.writeLock().lock();

        try {
            boolean result = false;

            if (!this.contains(tran)) {

                this.transactions.add(tran);

                /* The cached list may already contain the transaction if it has not been initialized yet */
                if (!this.getCachedSortedTransactionList().contains(tran)) {
                    this.getCachedSortedTransactionList().add(tran);
                    Collections.sort(this.getCachedSortedTransactionList());
                }

                this.clearCachedBalances();

                result = true;
            } else {
                logger
                    .log(Level.SEVERE,
                        "Account: {0}({1}){2}Already have transaction ID: {3}",
                        new Object[] { this.getName(), this.hashCode(), System.lineSeparator(), tran.hashCode() });
            }

            return result;
        } finally {
            this.transactionLock.writeLock().unlock();
        }
    }

    /**
     * Removes the specified transaction from this account.
     *
     * @param tran the {@code Transaction} to be removed
     * @return {@code true} the transaction removal was successful {@code false} the transaction could not be found
     * within this account
     */
    boolean removeTransaction(final Transaction tran) {
        this.transactionLock.writeLock().lock();

        try {
            boolean result = false;

            if (this.contains(tran)) {
                this.transactions.remove(tran);
                this.getCachedSortedTransactionList().remove(tran);
                this.clearCachedBalances();

                result = true;
            } else {
                Logger
                    .getLogger(Account.class.toString())
                        .log(Level.SEVERE,
                            "Account: {0}({1}){2}Did not contain transaction ID: {3}",
                            new Object[] { this.getName(), this.getUuid(), System.lineSeparator(), tran.getUuid() });
            }

            return result;
        } finally {
            this.transactionLock.writeLock().unlock();
        }
    }

    /**
     * Determines if the specified transaction is attach to this account.
     *
     * @param tran the {@code Transaction} to look for
     * @return {@code true} the transaction is attached to this account {@code false} the transaction is not attached
     * to this account
     */
    public boolean contains(final Transaction tran) {
        this.transactionLock.readLock().lock();

        try {
            return this.transactions.contains(tran);
        } finally {
            this.transactionLock.readLock().unlock();
        }
    }

    /**
     * Determine if the supplied account is a child of this account.
     *
     * @param account to check
     * @return true if the supplied account is a child of this account
     */
    public boolean contains(final Account account) {
        this.childLock.readLock().lock();

        try {
            return this.cachedSortedChildren.contains(account);
        } finally {
            this.childLock.readLock().unlock();
        }
    }

    /**
     * Returns a sorted list of transactions for this account that is unmodifiable.
     *
     * @return List of transactions
     */
    @NotNull
    public List<Transaction> getSortedTransactionList() {
        this.transactionLock.readLock().lock();

        try {
            return Collections.unmodifiableList(this.getCachedSortedTransactionList());
        } finally {
            this.transactionLock.readLock().unlock();
        }
    }

    /**
     * Returns the transaction at the specified index.
     *
     * @param index the index of the transaction to return.
     * @return the transaction at the specified index.
     * @throws IndexOutOfBoundsException if the index is out of bounds
     */
    @NotNull
    public Transaction getTransactionAt(final int index) {
        this.transactionLock.readLock().lock();

        try {
            return this.getCachedSortedTransactionList().get(index);
        } finally {
            this.transactionLock.readLock().unlock();
        }
    }

    /**
     * Returns the number of transactions attached to this account.
     *
     * @return the number of transactions attached to this account.
     */
    public int getTransactionCount() {
        this.transactionLock.readLock().lock();

        try {
            return this.transactions.size();
        } finally {
            this.transactionLock.readLock().unlock();
        }
    }

    /**
     * Searches through the transactions and determines the next largest
     * transaction number.
     *
     * @return The next check number; and empty String if numbers are not found
     */
    @NotNull
    public String getNextTransactionNumber() {
        this.transactionLock.readLock().lock();

        try {
            int number = 0;

            for (final Transaction tran : this.transactions) {
                if (numberPattern.matcher(tran.getNumber()).matches()) {
                    try {
                        number = Math.max(number, Integer.parseInt(tran.getNumber()));
                    } catch (NumberFormatException e) {
                        logger.log(Level.INFO, "Number regex failed", e);
                    }
                }
            }

            if (number == 0) {
                return "";
            }

            return Integer.toString(number + 1);
        } finally {
            this.transactionLock.readLock().unlock();
        }
    }

    /**
     * Add account child account given it's reference.
     *
     * @param child The child account to add to this account.
     * @return {@code true} if the account was added successfully, {@code false} otherwise.
     */
    boolean addChild(final Account child) {
        this.childLock.writeLock().lock();

        try {
            boolean result = false;

            if (!this.children.contains(child) && (child != this)) {
                if (child.setParent(this)) {
                    this.children.add(child);
                    result = true;

                    this.cachedSortedChildren.add(child);
                    Collections.sort(this.cachedSortedChildren);
                }
            }

            return result;
        } finally {
            this.childLock.writeLock().unlock();
        }
    }

    /**
     * Removes account child account. The reference to the parent(this) is left so that the parent can be discovered.
     *
     * @param child The child account to remove.
     * @return {@code true} if the specific account was account child of this account, {@code false} otherwise.
     */
    boolean removeChild(final Account child) {
        this.childLock.writeLock().lock();

        try {
            boolean result = false;

            if (this.children.remove(child)) {
                result = true;

                this.cachedSortedChildren.remove(child);
            }
            return result;
        } finally {
            this.childLock.writeLock().unlock();
        }
    }

    /**
     * Returns a sorted list of the children.  A protective copy is returned to protect against concurrency issues.
     *
     * @return List of children
     */
    public List<Account> getChildren() {
        this.childLock.readLock().lock();

        try {
            return new ArrayList<>(this.cachedSortedChildren);
        } finally {
            this.childLock.readLock().unlock();
        }
    }

    /**
     * Returns a sorted list of the children.  A protective copy is returned to protect against concurrency issues.
     *
     * @param comparator {@code Comparator} to use
     * @return List of children
     */
    public List<Account> getChildren(final Comparator<? super Account> comparator) {
        List<Account> accountChildren = this.getChildren();
        accountChildren.sort(comparator);

        return accountChildren;
    }

    /**
     * Returns the index of the specified {@code Transaction} within this {@code Account}.
     *
     * @param tran the {@code Transaction} to look for
     * @return The index of the {@code Transaction}, -1 if this
     * {@code Account} does not contain the {@code Transaction}.
     */
    public int indexOf(final Transaction tran) {
        this.transactionLock.readLock().lock();

        try {
            return this.getCachedSortedTransactionList().indexOf(tran);
        } finally {
            this.transactionLock.readLock().unlock();
        }
    }

    /**
     * Returns the number of children this account has.
     *
     * @return the number of children this account has.
     */
    public int getChildCount() {
        this.childLock.readLock().lock();

        try {
            return this.cachedSortedChildren.size();
        } finally {
            this.childLock.readLock().unlock();
        }
    }

    /**
     * Returns the parent account.
     *
     * @return the parent of this account, null is this account is not account child
     */
    public Account getParent() {
        this.childLock.readLock().lock();

        try {
            return this.parentAccount;
        } finally {
            this.childLock.readLock().unlock();
        }
    }

    /**
     * Sets the parent of this {@code Account}.
     *
     * @param account The new parent {@code Account}
     * @return {@code true} is successful
     */
    public boolean setParent(final Account account) {
        this.childLock.writeLock().lock();

        try {
            boolean result = false;

            if (account != this) {
                this.parentAccount = account;
                result = true;
            }

            return result;
        } finally {
            this.childLock.writeLock().unlock();
        }
    }

    /**
     * Determines is this {@code Account} has any child{@code Account}.
     *
     * @return {@code true} is this {@code Account} has children, {@code false} otherwise.
     */
    public boolean isParent() {
        this.childLock.readLock().lock();

        try {
            return !this.cachedSortedChildren.isEmpty();
        } finally {
            this.childLock.readLock().unlock();
        }
    }

    /**
     * The account balance is cached to improve performance and reduce thrashing
     * of the GC system. The accountBalance is reset when transactions are added
     * and removed and lazily recalculated.
     *
     * @return the balance of this account
     */
    public BigDecimal getBalance() {
        this.transactionLock.readLock().lock();

        try {
            if (this.accountBalance != null) {
                return this.accountBalance;
            }
            return this.accountBalance = this.getProxy().getBalance();
        } finally {
            this.transactionLock.readLock().unlock();
        }
    }

    /**
     * The account balance is cached to improve performance and reduce thrashing
     * of the GC system. The accountBalance is rest when transactions are added
     * and removed and lazily recalculated.
     *
     * @param node CurrencyNode to get balance against
     * @return the balance of this account
     */
    private BigDecimal getBalance(final CurrencyNode node) {
        this.transactionLock.readLock().lock();

        try {
            return this.adjustForExchangeRate(this.getBalance(), node);
        } finally {
            this.transactionLock.readLock().unlock();
        }
    }

    /**
     * Get the account balance up to the specified index using the natural
     * transaction sort order.
     *
     * @param index the balance of this account at the specified index.
     * @return the balance of this account at the specified index.
     */
    private BigDecimal getBalanceAt(final int index) {
        this.transactionLock.readLock().lock();

        try {
            return this.getProxy().getBalanceAt(index);
        } finally {
            this.transactionLock.readLock().unlock();
        }
    }

    /**
     * Get the account balance up to the specified transaction using the natural
     * transaction sort order.
     *
     * @param transaction reference transaction for running balance.  Must be contained within the account
     * @return the balance of this account at the specified transaction
     */
    public BigDecimal getBalanceAt(final Transaction transaction) {
        this.transactionLock.readLock().lock();

        try {
            final int index = this.indexOf(transaction);

            if (index >= 0) {
                return this.getBalanceAt(index);
            }

            return BigDecimal.ZERO;
        } finally {
            this.transactionLock.readLock().unlock();
        }
    }

    /**
     * The reconciled balance is cached to improve performance and reduce
     * thrashing of the GC system. The reconciledBalance is reset when
     * transactions are added and removed and lazily recalculated.
     *
     * @return the reconciled balance of this account
     */
    public BigDecimal getReconciledBalance() {
        this.transactionLock.readLock().lock();

        try {
            if (this.reconciledBalance != null) {
                return this.reconciledBalance;
            }

            return this.reconciledBalance = this.getProxy().getReconciledBalance();
        } finally {
            this.transactionLock.readLock().unlock();
        }
    }

    private BigDecimal getReconciledBalance(final CurrencyNode node) {
        return this.adjustForExchangeRate(this.getReconciledBalance(), node);
    }

    private BigDecimal adjustForExchangeRate(final BigDecimal amount, final CurrencyNode node) {
        if (node.equals(this.getCurrencyNode())) { // child has the same commodity type
            return amount;
        }

        // the account has a different currency, use the last known exchange rate
        return amount.multiply(this.getCurrencyNode().getExchangeRate(node));
    }

    /**
     * Returns the date of the first unreconciled transaction.
     *
     * @return Date of first unreconciled transaction
     */
    public LocalDate getFirstUnreconciledTransactionDate() {
        this.transactionLock.readLock().lock();

        try {
            LocalDate date = null;

            for (final Transaction transaction : this.getSortedTransactionList()) {
                if (transaction.getReconciled(this) != ReconciledState.RECONCILED) {
                    date = transaction.getLocalDate();
                    break;
                }
            }

            if (date == null) {
                date = this.getCachedSortedTransactionList().get(this.getTransactionCount() - 1).getLocalDate();
            }

            return date;
        } finally {
            this.transactionLock.readLock().unlock();
        }
    }

    /**
     * Get the default opening balance for reconciling the account.
     *
     * @return Opening balance for reconciling the account
     * @see AccountProxy#getOpeningBalanceForReconcile()
     */
    public BigDecimal getOpeningBalanceForReconcile() {
        return this.getProxy().getOpeningBalanceForReconcile();
    }

    /**
     * Returns the balance of the account plus any child accounts.
     *
     * @return the balance of this account including the balance of any child
     * accounts.
     */
    public BigDecimal getTreeBalance() {
        this.transactionLock.readLock().lock();
        this.childLock.readLock().lock();

        try {
            BigDecimal balance = this.getBalance();

            for (final Account child : this.cachedSortedChildren) {
                balance = balance.add(child.getTreeBalance(this.getCurrencyNode()));
            }

            return balance;
        } finally {
            this.transactionLock.readLock().unlock();
            this.childLock.readLock().unlock();
        }
    }

    /**
     * Returns the balance of the account plus any child accounts.
     *
     * @param endDate The inclusive end date
     * @param node The commodity to convert balance to
     *
     * @return the balance of this account including the balance of any child
     * accounts.
     */
    public BigDecimal getTreeBalance(final LocalDate endDate, final CurrencyNode node) {
        this.transactionLock.readLock().lock();
        this.childLock.readLock().lock();

        try {
            BigDecimal balance = this.getBalance(endDate, node);

            for (final Account child : this.cachedSortedChildren) {
                balance = balance.add(child.getTreeBalance(endDate, node));
            }

            return balance;
        } finally {
            this.transactionLock.readLock().unlock();
            this.childLock.readLock().unlock();
        }
    }

    /**
     * Returns the balance of the account plus any child accounts. The balance
     * is adjusted to the current exchange rate of the supplied commodity if
     * needed.
     *
     * @param node The commodity to convert balance to
     * @return the balance of this account including the balance of any child
     * accounts.
     */
    private BigDecimal getTreeBalance(final CurrencyNode node) {
        this.transactionLock.readLock().lock();
        this.childLock.readLock().lock();

        try {
            BigDecimal balance = this.getBalance(node);

            for (final Account child : this.cachedSortedChildren) {
                balance = balance.add(child.getTreeBalance(node));
            }
            return balance;
        } finally {
            this.transactionLock.readLock().unlock();
            this.childLock.readLock().unlock();
        }
    }

    /**
     * Returns the reconciled balance of the account plus any child accounts.
     * The balance is adjusted to the current exchange rate of the supplied
     * commodity if needed.
     *
     * @param node The commodity to convert balance to
     * @return the balance of this account including the balance of any child
     * accounts.
     */
    private BigDecimal getReconciledTreeBalance(final CurrencyNode node) {
        this.transactionLock.readLock().lock();
        this.childLock.readLock().lock();

        try {
            BigDecimal balance = this.getReconciledBalance(node);

            for (final Account child : this.cachedSortedChildren) {
                balance = balance.add(child.getReconciledTreeBalance(node));
            }
            return balance;
        } finally {
            this.transactionLock.readLock().unlock();
            this.childLock.readLock().unlock();
        }
    }

    /**
     * Returns the reconciled balance of the account plus any child accounts.
     *
     * @return the balance of this account including the balance of any child
     * accounts.
     */
    public BigDecimal getReconciledTreeBalance() {
        this.transactionLock.readLock().lock();
        this.childLock.readLock().lock();

        try {
            BigDecimal balance = this.getReconciledBalance();

            for (final Account child : this.cachedSortedChildren) {
                balance = balance.add(child.getReconciledTreeBalance(this.getCurrencyNode()));
            }
            return balance;
        } finally {
            this.transactionLock.readLock().unlock();
            this.childLock.readLock().unlock();
        }
    }

    /**
     * Returns the balance of the transactions inclusive of the start and end
     * dates.
     *
     * @param start The inclusive start date
     * @param end   The inclusive end date
     * @return The ending balance
     */
    public BigDecimal getBalance(final LocalDate start, final LocalDate end) {
        Objects.requireNonNull(start);
        Objects.requireNonNull(end);

        this.transactionLock.readLock().lock();

        try {
            return this.getProxy().getBalance(start, end);
        } finally {
            this.transactionLock.readLock().unlock();
        }
    }

    /**
     * Returns the account balance up to and inclusive of the supplied date. The
     * returned balance is converted to the specified commodity.
     *
     * @param startDate start date
     * @param endDate   end date
     * @param node      The commodity to convert balance to
     * @return the account balance
     */
    public BigDecimal getBalance(final LocalDate startDate, final LocalDate endDate, final CurrencyNode node) {
        this.transactionLock.readLock().lock();

        try {
            return this.adjustForExchangeRate(this.getBalance(startDate, endDate), node);
        } finally {
            this.transactionLock.readLock().unlock();
        }
    }

    /**
     * Returns the full inclusive ancestry of this
     * {@code Account}.
     *
     * @return {@code List} of accounts
     */
    public List<Account> getAncestors() {
        List<Account> list = new ArrayList<>();
        list.add(this);

        Account parent = this.getParent();

        while (parent != null) {
            list.add(parent);
            parent = parent.getParent();
        }

        return list;
    }

    /**
     * Returns the the balance of the account plus any child accounts inclusive
     * of the start and end dates.
     *
     * @param start start date
     * @param end   end date
     * @param node  CurrencyNode to use for balance
     * @return account balance
     */
    public BigDecimal getTreeBalance(final LocalDate start, final LocalDate end, final CurrencyNode node) {
        Objects.requireNonNull(start);
        Objects.requireNonNull(end);

        this.transactionLock.readLock().lock();
        this.childLock.readLock().lock();

        try {
            BigDecimal returnValue = this.getBalance(start, end, node);

            for (final Account child : this.cachedSortedChildren) {
                returnValue = returnValue.add(child.getTreeBalance(start, end, node));
            }
            return returnValue;
        } finally {
            this.transactionLock.readLock().unlock();
            this.childLock.readLock().unlock();
        }
    }

    /**
     * Returns the account balance up to and inclusive of the supplied localDate.
     *
     * @param localDate The inclusive ending localDate
     * @return The ending balance
     */
    public BigDecimal getBalance(final LocalDate localDate) {
        this.transactionLock.readLock().lock();

        try {
            return this.getProxy().getBalance(localDate);
        } finally {
            this.transactionLock.readLock().unlock();
        }
    }

    /**
     * Returns the account balance up to and inclusive of the supplied date. The
     * returned balance is converted to the specified commodity.
     *
     * @param node The commodity to convert balance to
     * @param date The inclusive ending date
     * @return The ending balance
     */
    public BigDecimal getBalance(final LocalDate date, final CurrencyNode node) {
        this.transactionLock.readLock().lock();

        try {
            return this.adjustForExchangeRate(this.getBalance(date), node);
        } finally {
            this.transactionLock.readLock().unlock();
        }
    }

    /**
     * Returns a {@code List} of {@code Transaction} that occur during the specified period.
     * The specified dates are inclusive.
     *
     * @param startDate starting date
     * @param endDate   ending date
     * @return a {@code List} of transactions that occurred within the specified dates
     */
    public List<Transaction> getTransactions(final LocalDate startDate, final LocalDate endDate) {
        this.transactionLock.readLock().lock();

        try {
            return this.transactions
                .parallelStream()
                    .filter(transaction -> DateUtils.after(transaction.getLocalDate(), startDate)
                        && DateUtils.before(transaction.getLocalDate(), endDate))
                    .sorted()
                    .collect(Collectors.toList());
        } finally {
            this.transactionLock.readLock().unlock();
        }
    }

    /**
     * Returns the commodity node for this account
     *
     * Note: method may not be final for Hibernate.
     *
     * @return the commodity node for this account.
     */
    public CurrencyNode getCurrencyNode() {
        return this.currencyNode;
    }

    /**
     * Sets the commodity node for this account.
     *
     * Note: method may not be final for Hibernate
     *
     * @param node The new commodity node for this account.
     */
    void setCurrencyNode(@NotNull final CurrencyNode node) {
        Objects.requireNonNull(node);

        if (!node.equals(this.currencyNode)) {
            this.currencyNode = node;

            this.clearCachedBalances(); // cached balances will need to be recalculated
        }
    }

    public boolean isLocked() {
        return this.locked;
    }

    public void setLocked(final boolean locked) {
        this.locked = locked;
    }

    public boolean isPlaceHolder() {
        return this.placeHolder;
    }

    public void setPlaceHolder(final boolean placeHolder) {
        this.placeHolder = placeHolder;
    }

    public String getDescription() {
        return this.description;
    }

    public void setDescription(final String desc) {
        this.description = desc;
    }

    public synchronized String getName() {
        return this.name;
    }

    public synchronized void setName(final String newName) {
        if (!newName.equals(this.name)) {
            this.name = newName;
        }
    }

    public synchronized String getPathName() {
        final Account parent = this.getParent();

        if ((parent != null) && (parent.getAccountType() != AccountType.ROOT)) {
            return parent.getPathName() + getAccountSeparator() + this.getName();
        }

        return this.getName(); // this account is at the root level
    }

    public AccountType getAccountType() {
        return this.accountType;
    }

    /**
     * Sets the account type
     *
     * Note: method may not be final for Hibernate
     *
     * @param type new account type
     */
    void setAccountType(final AccountType type) {
        Objects.requireNonNull(type);

        if ((this.accountType != null) && !this.accountType.isMutable()) {
            throw new EngineException("Immutable account type");
        }

        this.accountType = type;

        this.proxy = null; // proxy will need to change
    }

    /**
     * Returns the visibility of the account.
     *
     * @return boolean is this account is visible, false otherwise
     */
    public boolean isVisible() {
        return this.visible;
    }

    /**
     * Changes the visibility of the account.
     *
     * @param visible the new account visibility
     */
    public void setVisible(final boolean visible) {
        this.visible = visible;
    }

    /**
     * Returns the notes for this account.
     *
     * @return the notes for this account
     */
    public String getNotes() {
        return this.notes;
    }

    /**
     * Sets the notes for this account.
     *
     * @param notes the notes for this account
     */
    public void setNotes(final String notes) {
        this.notes = notes;
    }

    /**
     * Compares two Account for ordering.  Returned sort order is consistent with JPA order.
     * The account name, and then account UUID is used1
     *
     * @param acc the {@code Account} to be compared.
     * @return the value {@code 0} if the argument Account is equal to this Account; account
     * value less than {@code 0} if this Account is before the Account argument; and
     * account value greater than {@code 0} if this Account is after the Account argument.
     */
    @Override
    public int compareTo(@NotNull final Account acc) {

        // Sort by name
        int result = this.getName().compareToIgnoreCase(acc.getName());
        if (result != 0) {
            return result;
        }

        // Sort of uuid after everything else fails.
        return this.getUuid().compareTo(acc.getUuid());
    }

    @Override
    public String toString() {
        return this.name;
    }

    @Override
    public boolean equals(final Object other) {
        return (this == other) || ((other instanceof Account) && this.getUuid().equals(((Account) other).getUuid()));
    }

    /**
     * User definable account code.  This can be used to manage sort order
     *
     * @return the user defined account code
     */
    public int getAccountCode() {
        return this.accountCode;
    }

    public void setAccountCode(final int accountCode) {
        this.accountCode = accountCode;
    }

    /**
     * Returns the account number. A non-null value is guaranteed
     *
     * @return the account number
     */
    public String getAccountNumber() {
        return this.accountNumber;
    }

    public void setAccountNumber(final String account) {
        this.accountNumber = account;
    }

    /**
     * Adds account commodity to the list and ensures duplicates are not added.
     * The list is sorted according to numeric code
     *
     * @param node SecurityNode to add
     * @return true if successful
     */
    boolean addSecurity(final SecurityNode node) {
        boolean result = false;

        if ((node != null) && this.memberOf(AccountGroup.INVEST) && !this.containsSecurity(node)) {
            this.securities.add(node);

            result = true;
        }

        return result;
    }

    /**
     * Removes a {@code SecurityNode} from the account.  If the {@code SecurityNode} is in use by transactions,
     * removal will be prohibited.
     *
     * @param node {@code SecurityNode} to remove
     * @return {@code true} if successful, {@code false} if used by a transaction or not an active {@code SecurityNode}
     */
    boolean removeSecurity(final SecurityNode node) {
        this.securitiesLock.writeLock().lock();

        try {
            boolean result = false;

            if (!this.getUsedSecurities().contains(node) && this.containsSecurity(node)) {
                this.securities.remove(node);
                result = true;
            }

            return result;
        } finally {
            this.securitiesLock.writeLock().unlock();
        }
    }

    public boolean containsSecurity(final SecurityNode node) {
        this.securitiesLock.readLock().lock();

        try {
            return this.securities.contains(node);
        } finally {
            this.securitiesLock.readLock().unlock();
        }
    }

    /**
     * Returns the market value of this account.
     *
     * @return market value of the account
     */
    public BigDecimal getMarketValue() {
        return this.getProxy().getMarketValue();
    }

    /**
     * Returns a defensive copy of the security set.
     *
     * @return a sorted set
     */
    public Set<SecurityNode> getSecurities() {
        this.securitiesLock.readLock().lock();

        try {
            return new TreeSet<>(this.securities);
        } finally {
            this.securitiesLock.readLock().unlock();
        }
    }

    /**
     * Returns a set of used SecurityNodes.
     *
     * @return a set of used SecurityNodes
     */
    public Set<SecurityNode> getUsedSecurities() {
        this.transactionLock.readLock().lock();
        this.securitiesLock.readLock().lock();

        try {
            return this.transactions
                .parallelStream()
                    .filter(t -> t instanceof InvestmentTransaction)
                    .map(t -> ((InvestmentTransaction) t).getSecurityNode())
                    .collect(Collectors.toCollection(TreeSet::new));
        } finally {
            this.securitiesLock.readLock().unlock();
            this.transactionLock.readLock().unlock();
        }
    }

    /**
     * Returns the cash balance of this account.
     *
     * @return Cash balance of the account
     */
    public BigDecimal getCashBalance() {
        Lock l = this.transactionLock.readLock();
        l.lock();

        try {
            return this.getProxy().getCashBalance();
        } finally {
            l.unlock();
        }
    }

    /**
     * Returns the depth of the account relative to the {@code RootAccount}.
     *
     * @return depth relative to the root
     */
    public int getDepth() {
        int depth = 0;

        Account parent = this.getParent();

        while (parent != null) {
            depth++;
            parent = parent.getParent();
        }

        return depth;
    }

    /**
     * Shortcut method to check account type.
     *
     * @param type AccountType to compare against
     * @return true if supplied AccountType match
     */
    public final boolean instanceOf(final AccountType type) {
        return this.getAccountType() == type;
    }

    /**
     * Shortcut method to check account group membership.
     *
     * @param group AccountGroup to compare against
     * @return true if this account belongs to the supplied group
     */
    public final boolean memberOf(final AccountGroup group) {
        return this.getAccountType().getAccountGroup() == group;
    }

    public String getBankId() {
        return this.bankId;
    }

    public void setBankId(final String bankId) {
        this.bankId = bankId;
    }

    public boolean isExcludedFromBudget() {
        return this.excludedFromBudget;
    }

    public void setExcludedFromBudget(boolean excludeFromBudget) {
        this.excludedFromBudget = excludeFromBudget;
    }

    /**
     * Amortization object for loan payments.
     *
     * @return {@code AmortizeObject} if not null
     */
    @Nullable
    public AmortizeObject getAmortizeObject() {
        return this.amortizeObject;
    }

    void setAmortizeObject(final AmortizeObject amortizeObject) {
        this.amortizeObject = amortizeObject;
    }

    /**
     * Sets an attribute for the {@code Account}.
     *
     * @param key   the attribute key
     * @param value the value. If null, the attribute will be removed
     */
    void setAttribute(@NotNull final String key, @Nullable final String value) {
        this.attributesLock.writeLock().lock();

        try {
            if (key.isEmpty()) {
                throw new EngineException("Attribute key may not be empty or null");
            }

            if (value == null) {
                this.attributes.remove(key);
            } else {
                this.attributes.put(key, value);
            }
        } finally {
            this.attributesLock.writeLock().unlock();
        }
    }

    /**
     * Returns an {@code Account} attribute.
     *
     * @param key the attribute key
     * @return the attribute if found
     * @see Engine#setAccountAttribute
     */
    @Nullable
    String getAttribute(@NotNull final String key) {
        this.attributesLock.readLock().lock();

        try {
            if (key.isEmpty()) {
                throw new EngineException("Attribute key may not be empty or null");
            }

            return this.attributes.get(key);
        } finally {
            this.attributesLock.readLock().unlock();
        }
    }

    /**
     * Provides access to a cached and sorted list of transactions. Direct access to the list
     * is for internal use only.
     *
     * @return List of sorted transactions
     * @see #getSortedTransactionList
     */
    private List<Transaction> getCachedSortedTransactionList() {

        // Lazy initialization
        if (this.cachedSortedTransactionList == null) {
            this.cachedSortedTransactionList = new ArrayList<>(this.transactions);
            Collections.sort(this.cachedSortedTransactionList);
        }

        return this.cachedSortedTransactionList;
    }

    /**
     * Required by XStream for proper initialization.
     *
     * @return Properly initialized Account
     */
    protected Object readResolve() {
        this.postLoad();
        return this;
    }

    @PostLoad
    private void postLoad() {
        this.transactionLock = new ReentrantReadWriteLock(true);
        this.childLock = new ReentrantReadWriteLock(true);
        this.securitiesLock = new ReentrantReadWriteLock(true);
        this.attributesLock = new ReentrantReadWriteLock(true);

        this.cachedSortedChildren = new ArrayList<>(this.children);
        Collections.sort(this.cachedSortedChildren); // JPA will be naturally sorted, but XML files will not
    }

    /**
     * Accounts should not be cloned.
     *
     * @return will result in a CloneNotSupportedException
     * @throws java.lang.CloneNotSupportedException will always occur
     */
    @Override
    public Object clone()
        throws CloneNotSupportedException {
        super.clone();
        throw new CloneNotSupportedException("Accounts may not be cloned");
    }
}
