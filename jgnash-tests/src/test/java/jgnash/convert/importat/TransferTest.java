package jgnash.convert.importat;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 *
 * @author randrae
 *
 */
public class TransferTest {

    // Arbeitsverzeichnis
    private java.nio.file.Path workingDir = java.nio.file.Path.of("c:/Temp/t1");

    /**
     * Öffnet eine jGnash-Engine aus einer XML-Datei.
     */
    protected jgnash.engine.Engine openEngine(java.lang.String newEngineName, @jgnash.util.NotNull java.lang.String newFileName)
        throws java.io.IOException {

        java.nio.file.Path dbPath = java.nio.file.Paths.get(this.workingDir.toString(), newFileName);
        if (java.nio.file.Files.exists(dbPath) == false) {
            throw new java.io.IOException(java.lang.String.format("%s existiert nicht!", dbPath.toString()));
        }

        java.lang.String engineName = java.util.Optional.ofNullable(newEngineName).orElse(jgnash.engine.EngineFactory.DEFAULT);

        return jgnash.engine.EngineFactory
            .bootLocalEngine(dbPath.toString(), engineName, jgnash.engine.EngineFactory.EMPTY_PASSWORD, jgnash.engine.DataStoreType.XML);
    }

    /**
     * Erstellt eine jGnash-Engine aus einer XML-Datei.
     * @param newEngineName  optional, Enginename
     * @param newFileName    obligatorisch, DB-Filename
     * @return
     * @throws java.io.IOException
     */
    protected jgnash.engine.Engine createEngine(java.lang.String newEngineName, @jgnash.util.NotNull java.lang.String newFileName)
        throws java.io.IOException {

        java.nio.file.Path dbPath = java.nio.file.Paths.get(this.workingDir.toString(), newFileName);
        jgnash.engine.EngineFactory.deleteDatabase(dbPath.toString());

        java.lang.String engineName = java.util.Optional.ofNullable(newEngineName).orElse(jgnash.engine.EngineFactory.DEFAULT);

        return jgnash.engine.EngineFactory
            .bootLocalEngine(dbPath.toString(), engineName, jgnash.engine.EngineFactory.EMPTY_PASSWORD, jgnash.engine.DataStoreType.XML);
    }

    /**
     * Öffnet eine Engine auf Basis einer lokalen XML-Datei und exportiert alle
     * Konten im OFX-Format.
     * @throws java.lang.Exception
     */
    @org.junit.jupiter.api.Test
    void testExportLocalXmlFile()
        throws java.lang.Exception {

        jgnash.engine.Engine engine = this.openEngine(null, "randrae_jgnash2.xml");
        assertNotNull(engine);

        for (jgnash.engine.Account acc : engine.getAccountList()) {
            java.nio.file.Path path = java.nio.file.Paths.get(this.workingDir.toString(), "expacc_" + acc.getName() + ".ofx");

            jgnash.convert.exportantur.ofx.OfxExport ofxExport = new jgnash.convert.exportantur.ofx.OfxExport(acc,
                java.time.LocalDate.of(2000, 1, 1), java.time.LocalDate.of(2020, 12, 31), path.toFile());
            ofxExport.exportAccount();
        }

        jgnash.engine.EngineFactory.closeEngine(engine.getName());
    }

    /**
     * Lädt die Transaktionen aus einem OFX-File
     * @throws java.lang.Exception
     */
    @org.junit.jupiter.api.Test
    void testTransferLocalXmlFile()
        throws java.lang.Exception {

        jgnash.engine.Engine engine = this.createEngine(null, "transfer_jgnash3.xml");
        assertNotNull(engine);

        // Anlage der Währung
        jgnash.engine.CurrencyNode defaultCurrency = jgnash.engine.DefaultCurrencies.buildCustomNode("EUR");
        engine.addCurrency(defaultCurrency);
        engine.setDefaultCurrency(defaultCurrency);

        // Anlage der Basiskonten
        jgnash.engine.Account ausgabenAccount = new jgnash.engine.Account(jgnash.engine.AccountType.EXPENSE, defaultCurrency);
        ausgabenAccount.setName("Ausgaben");
        engine.addAccount(engine.getRootAccount(), ausgabenAccount);

        // Öffnet einen speziellen OFX-Export und importiert die Trnasaktionen
        java.nio.file.Path path = java.nio.file.Paths.get(this.workingDir.toString(), "expacc_Ausgaben.ofx");
        assertTrue(java.nio.file.Files.exists(path));

        jgnash.convert.importat.ofx.OfxBank ofxBank = jgnash.convert.importat.ofx.OfxV2Parser.parse(path);
        assertNotNull(ofxBank);

        for (final jgnash.convert.importat.ImportTransaction ofxTransaction : ofxBank.getTransactions()) {

            // do not import matched transactions
            if ((ofxTransaction.getState() == ImportState.NEW) || (ofxTransaction.getState() == ImportState.NOT_EQUAL)) {
                jgnash.engine.Transaction transaction = null;

                if (ofxTransaction.isInvestmentTransaction() == false) {
                    if (ausgabenAccount.equals(ofxTransaction.getAccount())) {
                        // single entry oTran
                        transaction = jgnash.engine.TransactionFactory
                            .generateSingleEntryTransaction(ausgabenAccount,
                                ofxTransaction.getAmount(),
                                ofxTransaction.getDatePosted(),
                                ofxTransaction.getMemo(),
                                ofxTransaction.getPayee(),
                                ofxTransaction.getCheckNumber());
                    } else {
                        // double entry
                        if (ofxTransaction.getAmount().signum() >= 0) {
                            transaction = jgnash.engine.TransactionFactory
                                .generateDoubleEntryTransaction(ausgabenAccount,
                                    ofxTransaction.getAccount(),
                                    ofxTransaction.getAmount().abs(),
                                    ofxTransaction.getDatePosted(),
                                    ofxTransaction.getMemo(),
                                    ofxTransaction.getPayee(),
                                    ofxTransaction.getCheckNumber());
                        } else {
                            transaction = jgnash.engine.TransactionFactory
                                .generateDoubleEntryTransaction(ofxTransaction.getAccount(),
                                    ausgabenAccount,
                                    ofxTransaction.getAmount().abs(),
                                    ofxTransaction.getDatePosted(),
                                    ofxTransaction.getMemo(),
                                    ofxTransaction.getPayee(),
                                    ofxTransaction.getCheckNumber());
                        }
                    }
                }

                // add the new transaction
                if (transaction != null) {
                    transaction.setFitid(ofxTransaction.getFITID());
                    engine.addTransaction(transaction);
                }
            }
        }

        jgnash.engine.EngineFactory.closeEngine(engine.getName());
    }

    /**
     * Transferiert die Transaktionen aus einer Engine in eine andere.
     * @throws java.lang.Exception
     */
    @org.junit.jupiter.api.Test
    void testTransferBetweenLocalXmlFile()
        throws java.lang.Exception {

        jgnash.engine.Engine destEngine = this.openEngine("destination", "randrae_jgnash3.xml");
        assertNotNull(destEngine);

        jgnash.engine.Engine srcEngine = this.openEngine("source", "randrae_jgnash2.xml");
        assertNotNull(srcEngine);

        for (jgnash.engine.Account srcAcc : srcEngine.getAccountList()) {

            for (jgnash.engine.Transaction srcTrx : srcAcc
                .getTransactions(java.time.LocalDate.of(2000, 1, 1), java.time.LocalDate.of(2020, 12, 31))) {

                final jgnash.engine.Transaction destTrx = new jgnash.engine.Transaction();
                destTrx.setDate(srcTrx.getLocalDate());
                destTrx.setNumber(srcTrx.getNumber());
                destTrx.setPayee(srcTrx.getPayee());
                destTrx.setMemo(srcTrx.getMemo());

                if (destEngine.getTransactionNumberList().contains(destTrx.getNumber()) == true) {
                    // Transaktion ist schon vorhanden
                    continue;
                }

                for (jgnash.engine.TransactionEntry srcTrxEntry : srcTrx.getTransactionEntries()) {
                    // Kontrolle etwaiger Unterkonten
                    jgnash.engine.Account srcDebitAccount = this.adjustAcccount(srcEngine, srcTrxEntry.getDebitAccount());
                    jgnash.engine.Account srcCreditAccount = this.adjustAcccount(srcEngine, srcTrxEntry.getCreditAccount());

                    //
                    jgnash.engine.Account destDebitAcc = this.createAcccount(destEngine, srcDebitAccount);
                    jgnash.engine.Account destCreditAcc = this.createAcccount(destEngine, srcCreditAccount);

                    // Kontrolle der Kontotypen
                    if (destDebitAcc.getAccountType().equals(destCreditAcc.getAccountType())) {
                        // Ausgaben werden auf "Giro" gebucht
                        if (destDebitAcc.getName().equals("Ausgaben")) {
                            destDebitAcc = destEngine.getAccountByName("Giro");
                        } else if (destCreditAcc.getName().equals("Ausgaben")) {
                            destCreditAcc = destEngine.getAccountByName("Giro");
                        }
                    }

                    final jgnash.engine.TransactionEntry entry = new jgnash.engine.TransactionEntry(destCreditAcc, destDebitAcc,
                        srcTrxEntry.getCreditAmount());
                    entry.setMemo(srcTrxEntry.getMemo());

                    destTrx.addTransactionEntry(entry);
                }

                destEngine.addTransaction(destTrx);
            }
        }

        jgnash.engine.EngineFactory.closeEngine(srcEngine.getName());
        jgnash.engine.EngineFactory.closeEngine(destEngine.getName());
    }

    /**
     * Rekursive Anlage der Accounts bis zur Wurzel
     * @param destEngine
     * @param srcAcc
     * @return
     */
    private jgnash.engine.Account createAcccount(jgnash.engine.Engine destEngine, jgnash.engine.Account srcAcc) {
        jgnash.engine.Account destAcc = destEngine.getAccountByName(srcAcc.getName());
        if (destAcc != null) {
            // Konto existiert schon
            return destAcc;
        }

        jgnash.engine.CurrencyNode currNode = destEngine.getCurrency(srcAcc.getCurrencyNode().getSymbol());
        if (currNode == null) {
            currNode = jgnash.engine.DefaultCurrencies.buildCustomNode(srcAcc.getCurrencyNode().getSymbol());
            currNode.setDescription(srcAcc.getCurrencyNode().getDescription());
            destEngine.addCurrency(currNode);
        }

        // Neuer Account
        destAcc = new jgnash.engine.Account(srcAcc.getAccountType(), currNode);
        destAcc.setName(srcAcc.getName());
        destAcc.setDescription(srcAcc.getDescription());
        destAcc.setAccountNumber(srcAcc.getAccountNumber());
        destAcc.setBankId(srcAcc.getBankId());
        destAcc.setAccountCode(srcAcc.getAccountCode());
        destAcc.setExcludedFromBudget(srcAcc.isExcludedFromBudget());
        destAcc.setLocked(false);
        destAcc.setPlaceHolder(false);
        destAcc.setVisible(true);

        // Einsortieren in die Kontenhierarchie
        if (srcAcc.getParent() == null) {
            // Neuanlage des Accounts in der Wurzel
            destEngine.addAccount(destEngine.getRootAccount(), destAcc);
        } else {
            // Anlage der Accounts bis zur Wurzel
            jgnash.engine.Account destRootAcc = this.createAcccount(destEngine, srcAcc.getParent());
            destEngine.addAccount(destRootAcc, destAcc);
        }

        java.util.List<jgnash.engine.Account> lstAcc = destEngine.getAccountList();
        java.lang.String b = java.lang.String.format("%s hat %d Konten", destEngine.getName(), lstAcc.size());

        return destAcc;
    }

    /**
     * Für ausgewählte Konten wird ein neues Unterkonto eingeführt
     *
     * @param srcEngine
     * @param newAcc
     * @return
     */
    private jgnash.engine.Account adjustAcccount(jgnash.engine.Engine srcEngine, jgnash.engine.Account newAcc) {
        jgnash.engine.Account destAcc = newAcc;
        if (newAcc.getName().equals("Essen")) {
            destAcc = srcEngine.getAccountByName("Mittagessen");
            if (destAcc != null) {
                // Konto existiert schon
                return destAcc;
            }

            // Unterkonto "Mittagessen"
            destAcc = new jgnash.engine.Account(newAcc.getAccountType(), newAcc.getCurrencyNode());
            destAcc.setName("Mittagessen");
            destAcc.setDescription("Mittagessen");
            destAcc.setAccountNumber("");
            destAcc.setBankId("");
            destAcc.setAccountCode(0);
            destAcc.setExcludedFromBudget(false);
            destAcc.setLocked(false);
            destAcc.setPlaceHolder(false);
            destAcc.setVisible(false);

            //
            srcEngine.addAccount(newAcc, destAcc);
        } else if (newAcc.getName().equals("Parken")) {
            destAcc = srcEngine.getAccountByName("Parken");
            if (destAcc != null) {
                // Konto existiert schon
                return destAcc;
            }

            // Unterkonto Auto?
            jgnash.engine.Account autoAcc = srcEngine.getAccountByName("Auto");
            if (autoAcc == null) {
                autoAcc = new jgnash.engine.Account(jgnash.engine.AccountType.EXPENSE, newAcc.getCurrencyNode());
                autoAcc.setName("Auto");
                autoAcc.setDescription("Ausgaben Auto");
                autoAcc.setAccountNumber("");
                autoAcc.setBankId("");
                autoAcc.setAccountCode(0);
                autoAcc.setExcludedFromBudget(false);
                autoAcc.setLocked(false);
                autoAcc.setPlaceHolder(true);
                autoAcc.setVisible(false);

                jgnash.engine.Account ausgabenAcc = srcEngine.getAccountByName("Ausgaben");
                srcEngine.addAccount(ausgabenAcc, autoAcc);
            }

            // Unterkonto Parken
            destAcc = new jgnash.engine.Account(newAcc.getAccountType(), newAcc.getCurrencyNode());
            destAcc.setName("Parken");
            destAcc.setDescription("Ausgaben Parken");
            destAcc.setAccountNumber("");
            destAcc.setBankId("");
            destAcc.setAccountCode(0);
            destAcc.setExcludedFromBudget(false);
            destAcc.setLocked(false);
            destAcc.setPlaceHolder(false);
            destAcc.setVisible(false);

            srcEngine.addAccount(autoAcc, destAcc);
        }

        return destAcc;
    }
}
