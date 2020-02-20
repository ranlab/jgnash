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
package jgnash.engine;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;

import io.github.glytching.junit.extension.folder.TemporaryFolder;
import io.github.glytching.junit.extension.folder.TemporaryFolderExtension;

/**
 * Abstract base for testing the core engine API's.
 *
 * @author Craig Cavanaugh
 */
@ExtendWith(TemporaryFolderExtension.class)
public abstract class AbstractEngineTest {

    protected TemporaryFolder testFolder;

    protected String database;

    protected Engine e;

    Account incomeAccount;

    Account expenseAccount;

    protected jgnash.engine.Account usdBankAccount;

    protected Account checkingAccount;

    Account equityAccount;

    protected Account investAccount;

    SecurityNode securityNode1;

    protected abstract Engine createEngine()
        throws IOException;

    @BeforeEach
    public void setUp(final TemporaryFolder testFolder)
        throws Exception {
        Locale.setDefault(Locale.US);

        this.testFolder = testFolder;

        this.e = this.createEngine();

        assertNotNull(this.e);

        this.e.setCreateBackups(false);

        // Creating currencies
        CurrencyNode defaultCurrency = DefaultCurrencies.buildCustomNode("USD");

        this.e.addCurrency(defaultCurrency);
        this.e.setDefaultCurrency(defaultCurrency);

        CurrencyNode cadCurrency = DefaultCurrencies.buildCustomNode("CAD");
        this.e.addCurrency(cadCurrency);

        // Creating accounts
        this.incomeAccount = new Account(AccountType.INCOME, defaultCurrency);
        this.incomeAccount.setName("Income Account");
        this.e.addAccount(this.e.getRootAccount(), this.incomeAccount);

        this.expenseAccount = new Account(AccountType.EXPENSE, defaultCurrency);
        this.expenseAccount.setName("Expense Account");
        this.e.addAccount(this.e.getRootAccount(), this.expenseAccount);

        this.usdBankAccount = new Account(AccountType.BANK, defaultCurrency);
        this.usdBankAccount.setName("USD Bank Account");
        this.usdBankAccount.setBankId("xyzabc");
        this.usdBankAccount.setAccountNumber("10001-A01");
        this.e.addAccount(this.e.getRootAccount(), this.usdBankAccount);

        this.checkingAccount = new Account(AccountType.CHECKING, defaultCurrency);
        this.checkingAccount.setName("Checking Account");
        this.checkingAccount.setBankId("xyzabc");
        this.checkingAccount.setAccountNumber("10001-C01");
        this.e.addAccount(this.e.getRootAccount(), this.checkingAccount);

        Account cadBankAccount = new Account(AccountType.BANK, cadCurrency);
        cadBankAccount.setName("CAD Bank Account");
        this.e.addAccount(this.e.getRootAccount(), cadBankAccount);

        this.equityAccount = new Account(AccountType.EQUITY, defaultCurrency);
        this.equityAccount.setName("Equity Account");
        this.e.addAccount(this.e.getRootAccount(), this.equityAccount);

        Account liabilityAccount = new Account(AccountType.LIABILITY, defaultCurrency);
        liabilityAccount.setName("Liability Account");
        this.e.addAccount(this.e.getRootAccount(), liabilityAccount);

        this.investAccount = new Account(AccountType.INVEST, defaultCurrency);
        this.investAccount.setName("Invest Account");
        this.e.addAccount(this.e.getRootAccount(), this.investAccount);

        // Creating securities
        this.securityNode1 = new SecurityNode(defaultCurrency);
        this.securityNode1.setSymbol("GOOGLE");
        assertTrue(this.e.addSecurity(this.securityNode1));

        // Adding security to the invest account
        List<SecurityNode> securityNodeList = new ArrayList<>();
        securityNodeList.add(this.securityNode1);
        assertTrue(this.e.updateAccountSecurities(this.investAccount, securityNodeList));
    }

    @AfterEach
    public void tearDown()
        throws IOException {
        EngineFactory.closeEngine(EngineFactory.DEFAULT);
        EngineFactory.deleteDatabase(this.database);

        Files.deleteIfExists(Paths.get(this.database));
    }
}
