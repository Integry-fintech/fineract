/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.fineract.integrationtests.savings;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.fineract.client.models.ChargeRequest;
import org.apache.fineract.client.models.GlobalConfigurationPropertyData;
import org.apache.fineract.client.models.PostSavingsAccountTransactionsRequest;
import org.apache.fineract.client.models.PostSavingsAccountsSavingsAccountIdChargesRequest;
import org.apache.fineract.client.models.PostSavingsAccountsSavingsAccountIdChargesSavingsAccountChargeIdRequest;
import org.apache.fineract.client.models.PostSavingsProductsRequest;
import org.apache.fineract.client.models.PutGlobalConfigurationsRequest;
import org.apache.fineract.client.models.SavingsAccountTransactionData;
import org.apache.fineract.client.util.CallFailedRuntimeException;
import org.apache.fineract.infrastructure.configuration.api.GlobalConfigurationConstants;
import org.apache.fineract.integrationtests.common.ClientHelper;
import org.apache.fineract.integrationtests.common.Utils;
import org.apache.fineract.integrationtests.common.charges.ChargesHelper;
import org.apache.fineract.integrationtests.savings.base.BaseSavingsIntegrationTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Charges in pivot mode: the summary and the running balance must match the transactions, both on an account with a
 * zero-interest pivot and on one that has no pivot yet, and a charge cannot be paid on or before the pivot date, the
 * same rule that applies to withdrawals.
 */
public class SavingsZeroInterestPivotChargeIntegrationTest extends BaseSavingsIntegrationTest {

    private static final String ACTIVATION_DATE = "01 September 2026";
    private static final String PIVOT_RUN_DATE = "02 September 2026";
    private static final String TRANSACTION_DATE = "03 September 2026";
    private static final LocalDate PIVOT_DATE = LocalDate.of(2026, 9, 1);

    private static final String PG_URL = env("FINERACT_IT_PG_URL", "jdbc:postgresql://localhost:5432/fineract_default");
    private static final String PG_USER = env("FINERACT_IT_PG_USER", "root");
    private static final String PG_PASSWORD = env("FINERACT_IT_PG_PASSWORD", "postgres");

    @Test
    public void paidChargeKeepsSummaryAndRunningBalanceWithZeroInterestPivot() {
        inPivotMode(() -> {
            final Long chargeId = createFlatCharge();
            final Long savingsId = openActiveAccountWithPivot();

            runAt(TRANSACTION_DATE, () -> {
                deposit(savingsId, TRANSACTION_DATE, new BigDecimal("20.00"));
                withdraw(savingsId, "100.00");
                final Long savingsChargeId = addCharge(savingsId, chargeId, TRANSACTION_DATE);
                payCharge(savingsId, savingsChargeId, TRANSACTION_DATE);

                assertSummary(savingsId, "913.00", "1020.00", "100.00", "7.00");
                assertLastRunningBalance(savingsId, "913.00");

                schedulerJobHelper.executeAndAwaitJobByShortName("SA_RBAL");
                assertSummary(savingsId, "913.00", "1020.00", "100.00", "7.00");
            });
        });
    }

    @Test
    public void chargeCannotBePaidOnTheZeroInterestPivotDate() {
        inPivotMode(() -> {
            final Long chargeId = createFlatCharge();
            final Long savingsId = openActiveAccountWithPivot();

            runAt(TRANSACTION_DATE, () -> {
                final Long savingsChargeId = addCharge(savingsId, chargeId, ACTIVATION_DATE);
                final CallFailedRuntimeException exception = Assertions.assertThrows(CallFailedRuntimeException.class,
                        () -> payCharge(savingsId, savingsChargeId, ACTIVATION_DATE));
                Assertions.assertEquals(403, exception.getResponse().code());
                Assertions.assertTrue(exception.getMessage().contains("error.msg.savings.transaction.is.not.allowed"),
                        exception::getMessage);

                assertSummary(savingsId, "1000.00", "1000.00", "0.00", "0.00");
            });
        });
    }

    @Test
    public void waivedChargeKeepsBalanceWithZeroInterestPivot() {
        inPivotMode(() -> {
            final Long chargeId = createFlatCharge();
            final Long savingsId = openActiveAccountWithPivot();

            runAt(TRANSACTION_DATE, () -> {
                final Long savingsChargeId = addCharge(savingsId, chargeId, TRANSACTION_DATE);
                ok(fineractClient().savingsAccountCharges.payOrWaiveSavingsAccountCharge(savingsId, savingsChargeId,
                        new PostSavingsAccountsSavingsAccountIdChargesSavingsAccountChargeIdRequest(), "waive"));

                assertSummary(savingsId, "1000.00", "1000.00", "0.00", "0.00");
                schedulerJobHelper.executeAndAwaitJobByShortName("SA_RBAL");
                assertSummary(savingsId, "1000.00", "1000.00", "0.00", "0.00");
            });
        });
    }

    @Test
    public void paidChargeKeepsSummaryAndRunningBalanceWithoutPivot() {
        inPivotMode(() -> {
            final Long chargeId = createFlatCharge();
            final Long savingsId = openActiveAccount();

            runAt(TRANSACTION_DATE, () -> {
                withdraw(savingsId, "100.00");
                final Long savingsChargeId = addCharge(savingsId, chargeId, TRANSACTION_DATE);
                payCharge(savingsId, savingsChargeId, TRANSACTION_DATE);

                assertSummary(savingsId, "893.00", "1000.00", "100.00", "7.00");
                assertLastRunningBalance(savingsId, "893.00");
            });
        });
    }

    @Test
    public void concurrentPaymentsOfTheSameChargePayItOnce() {
        Assumptions.assumeTrue(databaseAvailable(), "requires a JDBC connection to the tenant database");
        inPivotMode(() -> {
            final Long chargeId = createFlatCharge();
            final Long savingsId = openActiveAccountWithPivot();

            runAt(TRANSACTION_DATE, () -> {
                final Long savingsChargeId = addCharge(savingsId, chargeId, TRANSACTION_DATE);

                final List<Integer> statuses = payTwiceWhileTheAccountIsLocked(savingsId, savingsChargeId);

                Assertions.assertEquals(1, statuses.stream().filter(status -> status == 200).count(),
                        () -> "exactly one payment must succeed, got " + statuses);
                assertSummary(savingsId, "993.00", "1000.00", "0.00", "7.00");
            });
        });
    }

    /**
     * Holds the account row lock from a JDBC connection while two payments of the same charge start, and releases it
     * once both are waiting for it. Returns their HTTP statuses.
     */
    private List<Integer> payTwiceWhileTheAccountIsLocked(Long savingsId, Long savingsChargeId) {
        final ExecutorService pool = Executors.newFixedThreadPool(2);
        try (Connection lock = DriverManager.getConnection(PG_URL, PG_USER, PG_PASSWORD);
                Connection monitor = DriverManager.getConnection(PG_URL, PG_USER, PG_PASSWORD)) {
            lock.setAutoCommit(false);
            try (Statement statement = lock.createStatement()) {
                statement.execute("SELECT id FROM m_savings_account WHERE id = " + savingsId + " FOR UPDATE");
            }
            final List<Future<Integer>> payments = List.of(pool.submit(() -> payChargeStatus(savingsId, savingsChargeId)),
                    pool.submit(() -> payChargeStatus(savingsId, savingsChargeId)));
            awaitSessionsWaitingForALock(monitor, 2);
            lock.rollback();
            final List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> payment : payments) {
                statuses.add(payment.get(2, TimeUnit.MINUTES));
            }
            return statuses;
        } catch (Exception e) {
            throw new IllegalStateException("could not run the concurrent payments", e);
        } finally {
            pool.shutdownNow();
        }
    }

    private static void awaitSessionsWaitingForALock(Connection monitor, int sessions) throws SQLException, InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        try (Statement statement = monitor.createStatement()) {
            while (System.nanoTime() < deadline) {
                try (ResultSet rs = statement.executeQuery(
                        "SELECT count(*) FROM pg_stat_activity " + "WHERE datname = current_database() AND wait_event_type = 'Lock'")) {
                    rs.next();
                    if (rs.getInt(1) >= sessions) {
                        return;
                    }
                }
                Thread.sleep(100);
            }
        }
        Assertions.fail(sessions + " requests did not wait for the account lock within 30 s");
    }

    private int payChargeStatus(Long savingsId, Long savingsChargeId) throws IOException {
        return fineractClient().savingsAccountCharges.payOrWaiveSavingsAccountCharge(savingsId, savingsChargeId,
                new PostSavingsAccountsSavingsAccountIdChargesSavingsAccountChargeIdRequest().amount(7.0f).dueDate(TRANSACTION_DATE)
                        .dateFormat(DATETIME_PATTERN).locale("en"),
                "paycharge").execute().code();
    }

    private static boolean databaseAvailable() {
        try (Connection connection = DriverManager.getConnection(PG_URL, PG_USER, PG_PASSWORD)) {
            return connection.isValid(5);
        } catch (SQLException e) {
            return false;
        }
    }

    private static String env(String name, String defaultValue) {
        final String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    /** Account with 1000.00 deposited on the activation date and a zero-interest pivot on that date. */
    private Long openActiveAccountWithPivot() {
        final Long savingsId = openActiveAccount();
        runAt(PIVOT_RUN_DATE, () -> schedulerJobHelper.executeAndAwaitJobByShortName("SA_ZIPV"));
        Assertions.assertEquals(PIVOT_DATE, interestPostedTillDate(savingsId), "the account must have its zero-interest pivot");
        return savingsId;
    }

    /** Account with 1000.00 deposited on the activation date and no pivot. */
    private Long openActiveAccount() {
        final Long clientId = clientHelper.createClient(ClientHelper.defaultClientCreationRequest()).getClientId();
        final Long productId = createProduct(zeroInterestProduct()).getResourceId();
        final Long savingsId = applySavingsAccount(applySavingsRequest(clientId, productId, ACTIVATION_DATE)).getSavingsId();
        runAt(ACTIVATION_DATE, () -> {
            approveSavingsAccount(savingsId, ACTIVATION_DATE);
            activateSavingsAccount(savingsId, ACTIVATION_DATE);
            deposit(savingsId, ACTIVATION_DATE, new BigDecimal("1000.00"));
        });
        Assertions.assertNull(interestPostedTillDate(savingsId), "the account must not have a pivot yet");
        return savingsId;
    }

    private LocalDate interestPostedTillDate(Long savingsId) {
        return ok(fineractClient().savingsAccounts.retrieveSavingsAccount(savingsId, false, null, "summary")).getSummary()
                .getInterestPostedTillDate();
    }

    private void inPivotMode(Runnable test) {
        final GlobalConfigurationPropertyData originalBackdated = globalConfigurationHelper
                .getGlobalConfigurationByName(GlobalConfigurationConstants.ALLOW_BACKDATED_TRANSACTION_BEFORE_INTEREST_POSTING);
        final GlobalConfigurationPropertyData originalRelaxedDays = globalConfigurationHelper.getGlobalConfigurationByName(
                GlobalConfigurationConstants.ALLOW_BACKDATED_TRANSACTION_BEFORE_INTEREST_POSTING_DATE_FOR_DAYS);
        final boolean originalSchedulerStatus = schedulerJobHelper.getSchedulerStatus();
        try {
            globalConfigurationHelper.updateGlobalConfiguration(
                    GlobalConfigurationConstants.ALLOW_BACKDATED_TRANSACTION_BEFORE_INTEREST_POSTING,
                    new PutGlobalConfigurationsRequest().enabled(false));
            globalConfigurationHelper.updateGlobalConfiguration(
                    GlobalConfigurationConstants.ALLOW_BACKDATED_TRANSACTION_BEFORE_INTEREST_POSTING_DATE_FOR_DAYS,
                    new PutGlobalConfigurationsRequest().enabled(false));
            Assertions.assertFalse(Boolean.TRUE.equals(globalConfigurationHelper
                    .getGlobalConfigurationByName(GlobalConfigurationConstants.ALLOW_BACKDATED_TRANSACTION_BEFORE_INTEREST_POSTING)
                    .getEnabled()), "pivot mode must be on");
            test.run();
        } finally {
            restoreConfiguration(GlobalConfigurationConstants.ALLOW_BACKDATED_TRANSACTION_BEFORE_INTEREST_POSTING, originalBackdated);
            restoreConfiguration(GlobalConfigurationConstants.ALLOW_BACKDATED_TRANSACTION_BEFORE_INTEREST_POSTING_DATE_FOR_DAYS,
                    originalRelaxedDays);
            schedulerJobHelper.updateSchedulerStatus(originalSchedulerStatus);
        }
    }

    private Long createFlatCharge() {
        return new ChargesHelper()
                .createCharges(new ChargeRequest().active(true).name(Utils.uniqueRandomStringGenerator("Pivot_Fee_", 6)).currencyCode("USD")
                        .amount(7.0d).chargeAppliesTo(2).chargeTimeType(2).chargeCalculationType(1).locale("en").penalty(false))
                .getResourceId();
    }

    private Long addCharge(Long savingsId, Long chargeId, String dueDate) {
        return ok(fineractClient().savingsAccountCharges.addSavingsAccountCharge(savingsId,
                new PostSavingsAccountsSavingsAccountIdChargesRequest().chargeId(chargeId).amount(7.0f).dueDate(dueDate)
                        .dateFormat(DATETIME_PATTERN).locale("en")))
                .getResourceId();
    }

    private void payCharge(Long savingsId, Long savingsChargeId, String date) {
        ok(fineractClient().savingsAccountCharges.payOrWaiveSavingsAccountCharge(savingsId, savingsChargeId,
                new PostSavingsAccountsSavingsAccountIdChargesSavingsAccountChargeIdRequest().amount(7.0f).dueDate(date)
                        .dateFormat(DATETIME_PATTERN).locale("en"),
                "paycharge"));
    }

    private void withdraw(Long savingsId, String amount) {
        ok(fineractClient().savingsTransactions.createSavingsAccountTransaction(savingsId,
                new PostSavingsAccountTransactionsRequest().dateFormat(DATETIME_PATTERN).locale("en").paymentTypeId(1)
                        .transactionAmount(new BigDecimal(amount)).transactionDate(TRANSACTION_DATE),
                "withdrawal"));
    }

    private void assertSummary(Long savingsId, String balance, String deposits, String withdrawals, String feeCharges) {
        final var summary = ok(fineractClient().savingsAccounts.retrieveSavingsAccount(savingsId, false, null, "summary")).getSummary();
        assertAmount("account balance", balance, summary.getAccountBalance());
        assertAmount("total deposits", deposits, summary.getTotalDeposits());
        assertAmount("total withdrawals", withdrawals, summary.getTotalWithdrawals());
        assertAmount("total fee charges", feeCharges, summary.getTotalFeeCharge());
    }

    private void assertLastRunningBalance(Long savingsId, String expected) {
        final List<SavingsAccountTransactionData> transactions = getTransactions(savingsId);
        final SavingsAccountTransactionData last = transactions.stream().filter(t -> !Boolean.TRUE.equals(t.getReversed()))
                .max((a, b) -> Long.compare(a.getId(), b.getId())).orElseThrow();
        assertAmount("running balance of the last transaction", expected, last.getRunningBalance());
    }

    private void assertAmount(String field, String expected, BigDecimal actual) {
        final BigDecimal actualOrZero = actual == null ? BigDecimal.ZERO : actual;
        Assertions.assertEquals(0, new BigDecimal(expected).compareTo(actualOrZero),
                () -> field + ": expected " + expected + " but was " + actualOrZero);
    }

    private PostSavingsProductsRequest zeroInterestProduct() {
        return new PostSavingsProductsRequest().locale("en").name(Utils.uniqueRandomStringGenerator("ZERO_INTEREST_", 6))
                .shortName(Utils.uniqueRandomStringGenerator("", 4)).description("Zero interest wallet with charges").currencyCode("USD")
                .digitsAfterDecimal(2).inMultiplesOf(0).nominalAnnualInterestRate(0.0)
                .interestCompoundingPeriodType(InterestPeriodType.DAILY).interestPostingPeriodType(InterestPeriodType.MONTHLY)
                .interestCalculationType(InterestCalculationType.DAILY_BALANCE).interestCalculationDaysInYearType(DaysInYearType.DAYS_365)
                .accountingRule(1).withdrawalFeeForTransfers(false).allowOverdraft(false).enforceMinRequiredBalance(false)
                .withHoldTax(false).isDormancyTrackingActive(false);
    }

    private void restoreConfiguration(final String name, final GlobalConfigurationPropertyData configuration) {
        globalConfigurationHelper.updateGlobalConfiguration(name,
                new PutGlobalConfigurationsRequest().enabled(configuration.getEnabled()).value(configuration.getValue()));
    }
}
