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

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.client.models.GlobalConfigurationPropertyData;
import org.apache.fineract.client.models.PostSavingsAccountTransactionsRequest;
import org.apache.fineract.client.models.PostSavingsProductsRequest;
import org.apache.fineract.client.models.PutGlobalConfigurationsRequest;
import org.apache.fineract.infrastructure.configuration.api.GlobalConfigurationConstants;
import org.apache.fineract.integrationtests.common.ClientHelper;
import org.apache.fineract.integrationtests.common.Utils;
import org.apache.fineract.integrationtests.savings.base.BaseSavingsIntegrationTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * The cost of a savings withdrawal must not grow with the account history.
 *
 * <p>
 * Each test opens two accounts that differ only in how many transactions they have before or after their zero-interest
 * pivot, and profiles a single withdrawal on each with {@code pg_stat_statements}:
 * <ul>
 * <li>history before the pivot: the withdrawal must not read it at all;</li>
 * <li>history after the pivot: it is read, but its child rows must be fetched in batches, not one statement per
 * transaction.</li>
 * </ul>
 *
 * <p>
 * Requires PostgreSQL with {@code shared_preload_libraries=pg_stat_statements} and a superuser connection to the tenant
 * database (FINERACT_IT_PG_URL, FINERACT_IT_PG_USER, FINERACT_IT_PG_PASSWORD). Otherwise it is skipped.
 */
@Slf4j
public class SavingsWithdrawalQueryScalingIntegrationTest extends BaseSavingsIntegrationTest {

    private static final String PG_URL = env("FINERACT_IT_PG_URL", "jdbc:postgresql://localhost:5432/fineract_default");
    private static final String PG_USER = env("FINERACT_IT_PG_USER", "root");
    private static final String PG_PASSWORD = env("FINERACT_IT_PG_PASSWORD", "postgres");

    private static final int SMALL_HISTORY = 5;
    private static final int LARGE_HISTORY = Integer.getInteger("fineract.it.savings.largeHistory", 150);

    private static final String ACTIVATION_DATE = "01 September 2026";
    private static final String PIVOT_RUN_DATE = "02 September 2026";
    private static final String WITHDRAWAL_DATE = "03 September 2026";

    private static final List<String> CHILD_TABLES = List.of("m_savings_account_charge_paid_by",
            "m_savings_account_transaction_tax_details", "m_payment_detail");
    private static final String TRANSACTION_TABLE = "m_savings_account_transaction";

    @Test
    public void withdrawalCostDoesNotGrowWithHistoryBeforeZeroInterestPivot() {
        final QueryProfile[] profiles = profileWithdrawals(SMALL_HISTORY, LARGE_HISTORY, SMALL_HISTORY, SMALL_HISTORY);
        final QueryProfile small = profiles[0];
        final QueryProfile large = profiles[1];

        final int historyDelta = LARGE_HISTORY - SMALL_HISTORY;
        assertChildStatementsDoNotGrow(small, large, historyDelta, "before the pivot");
        final long rowsDelta = large.rows(TRANSACTION_TABLE) - small.rows(TRANSACTION_TABLE);
        Assertions.assertTrue(rowsDelta < historyDelta / 2, () -> "rows read from " + TRANSACTION_TABLE + " grew by " + rowsDelta + " for "
                + historyDelta + " more transactions before the pivot (the full history is loaded)");
    }

    @Test
    public void withdrawalChildStatementsDoNotGrowWithHistoryAfterZeroInterestPivot() {
        final QueryProfile[] profiles = profileWithdrawals(SMALL_HISTORY, SMALL_HISTORY, SMALL_HISTORY, LARGE_HISTORY / 2);
        assertChildStatementsDoNotGrow(profiles[0], profiles[1], LARGE_HISTORY / 2 - SMALL_HISTORY, "after the pivot");
    }

    private static void assertChildStatementsDoNotGrow(QueryProfile small, QueryProfile large, int historyDelta, String where) {
        for (String table : CHILD_TABLES) {
            final long delta = large.calls(table) - small.calls(table);
            Assertions.assertTrue(delta <= 2, () -> "statements against " + table + " grew by " + delta + " for " + historyDelta
                    + " more transactions " + where + " (one statement per transaction)");
        }
    }

    /**
     * Opens two zero-interest accounts with the given number of deposits before and after their pivot, and profiles one
     * withdrawal on each. Returns the small account profile first.
     */
    private QueryProfile[] profileWithdrawals(int smallBefore, int largeBefore, int smallAfter, int largeAfter) {
        Assumptions.assumeTrue(statementStatisticsAvailable(), "requires PostgreSQL with pg_stat_statements");

        final GlobalConfigurationPropertyData originalBackdated = globalConfigurationHelper
                .getGlobalConfigurationByName(GlobalConfigurationConstants.ALLOW_BACKDATED_TRANSACTION_BEFORE_INTEREST_POSTING);
        final GlobalConfigurationPropertyData originalRelaxedDays = globalConfigurationHelper.getGlobalConfigurationByName(
                GlobalConfigurationConstants.ALLOW_BACKDATED_TRANSACTION_BEFORE_INTEREST_POSTING_DATE_FOR_DAYS);
        final boolean originalSchedulerStatus = schedulerJobHelper.getSchedulerStatus();

        try {
            // pivot mode, as in production
            globalConfigurationHelper.updateGlobalConfiguration(
                    GlobalConfigurationConstants.ALLOW_BACKDATED_TRANSACTION_BEFORE_INTEREST_POSTING,
                    new PutGlobalConfigurationsRequest().enabled(false));
            globalConfigurationHelper.updateGlobalConfiguration(
                    GlobalConfigurationConstants.ALLOW_BACKDATED_TRANSACTION_BEFORE_INTEREST_POSTING_DATE_FOR_DAYS,
                    new PutGlobalConfigurationsRequest().enabled(false));

            final Long productId = createProduct(zeroInterestProduct()).getResourceId();
            final Long smallSavingsId = openAccount(productId);
            final Long largeSavingsId = openAccount(productId);

            runAt(ACTIVATION_DATE, () -> {
                activate(smallSavingsId);
                activate(largeSavingsId);
                depositTimes(smallSavingsId, ACTIVATION_DATE, smallBefore);
                depositTimes(largeSavingsId, ACTIVATION_DATE, largeBefore);
            });
            runAt(PIVOT_RUN_DATE, () -> schedulerJobHelper.executeAndAwaitJobByShortName("SA_ZIPV"));

            final QueryProfile[] profiles = new QueryProfile[2];
            runAt(WITHDRAWAL_DATE, () -> {
                depositTimes(smallSavingsId, WITHDRAWAL_DATE, smallAfter);
                depositTimes(largeSavingsId, WITHDRAWAL_DATE, largeAfter);
                schedulerJobHelper.updateSchedulerStatus(false);
                profiles[0] = profileWithdrawal(smallSavingsId);
                profiles[1] = profileWithdrawal(largeSavingsId);
            });
            log.info("Withdrawal with {} transactions before and {} after the pivot: {}", smallBefore, smallAfter, profiles[0].describe());
            log.info("Withdrawal with {} transactions before and {} after the pivot: {}", largeBefore, largeAfter, profiles[1].describe());
            return profiles;
        } finally {
            restoreConfiguration(GlobalConfigurationConstants.ALLOW_BACKDATED_TRANSACTION_BEFORE_INTEREST_POSTING, originalBackdated);
            restoreConfiguration(GlobalConfigurationConstants.ALLOW_BACKDATED_TRANSACTION_BEFORE_INTEREST_POSTING_DATE_FOR_DAYS,
                    originalRelaxedDays);
            schedulerJobHelper.updateSchedulerStatus(originalSchedulerStatus);
        }
    }

    private Long openAccount(Long productId) {
        final Long clientId = clientHelper.createClient(ClientHelper.defaultClientCreationRequest()).getClientId();
        return applySavingsAccount(applySavingsRequest(clientId, productId, ACTIVATION_DATE)).getSavingsId();
    }

    private void activate(Long savingsId) {
        approveSavingsAccount(savingsId, ACTIVATION_DATE);
        activateSavingsAccount(savingsId, ACTIVATION_DATE);
    }

    private void depositTimes(Long savingsId, String date, int times) {
        for (int i = 0; i < times; i++) {
            deposit(savingsId, date, new BigDecimal("10.00"));
        }
    }

    private QueryProfile profileWithdrawal(Long savingsId) {
        try (Connection connection = DriverManager.getConnection(PG_URL, PG_USER, PG_PASSWORD);
                Statement statement = connection.createStatement()) {
            statement.execute("SELECT pg_stat_statements_reset()");
            ok(fineractClient().savingsTransactions
                    .createSavingsAccountTransaction(
                            savingsId, new PostSavingsAccountTransactionsRequest().dateFormat(DATETIME_PATTERN).locale("en")
                                    .paymentTypeId(1).transactionAmount(new BigDecimal("1.00")).transactionDate(WITHDRAWAL_DATE),
                            "withdrawal"));
            return QueryProfile.read(statement);
        } catch (SQLException e) {
            throw new IllegalStateException("could not read pg_stat_statements", e);
        }
    }

    private static boolean statementStatisticsAvailable() {
        try (Connection connection = DriverManager.getConnection(PG_URL, PG_USER, PG_PASSWORD);
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE EXTENSION IF NOT EXISTS pg_stat_statements");
            statement.executeQuery("SELECT 1 FROM pg_stat_statements LIMIT 1").close();
            return true;
        } catch (SQLException e) {
            log.warn("pg_stat_statements is not available at {}: {}", PG_URL, e.getMessage());
            return false;
        }
    }

    private PostSavingsProductsRequest zeroInterestProduct() {
        return new PostSavingsProductsRequest().locale("en").name(Utils.uniqueRandomStringGenerator("ZERO_INTEREST_", 6))
                .shortName(Utils.uniqueRandomStringGenerator("", 4)).description("Zero interest wallet for query scaling test")
                .currencyCode("USD").digitsAfterDecimal(2).inMultiplesOf(0).nominalAnnualInterestRate(0.0)
                .interestCompoundingPeriodType(InterestPeriodType.DAILY).interestPostingPeriodType(InterestPeriodType.MONTHLY)
                .interestCalculationType(InterestCalculationType.DAILY_BALANCE).interestCalculationDaysInYearType(DaysInYearType.DAYS_365)
                .accountingRule(1).withdrawalFeeForTransfers(false).allowOverdraft(false).enforceMinRequiredBalance(false)
                .withHoldTax(false).isDormancyTrackingActive(false);
    }

    private void restoreConfiguration(final String name, final GlobalConfigurationPropertyData configuration) {
        globalConfigurationHelper.updateGlobalConfiguration(name,
                new PutGlobalConfigurationsRequest().enabled(configuration.getEnabled()).value(configuration.getValue()));
    }

    private static String env(String name, String defaultValue) {
        final String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    /** Statements executed in the tenant database since the last reset, as reported by pg_stat_statements. */
    private record QueryProfile(List<Entry> entries) {

        private record Entry(String query, long calls, long rows) {
        }

        static QueryProfile read(Statement statement) throws SQLException {
            final List<Entry> entries = new ArrayList<>();
            try (ResultSet rs = statement.executeQuery("SELECT s.query, s.calls, s.rows FROM pg_stat_statements s "
                    + "JOIN pg_database d ON d.oid = s.dbid WHERE d.datname = current_database()")) {
                while (rs.next()) {
                    entries.add(new Entry(rs.getString(1), rs.getLong(2), rs.getLong(3)));
                }
            }
            return new QueryProfile(entries);
        }

        long calls(String table) {
            return selectsFrom(table).stream().mapToLong(Entry::calls).sum();
        }

        long rows(String table) {
            return selectsFrom(table).stream().mapToLong(Entry::rows).sum();
        }

        private List<Entry> selectsFrom(String table) {
            final Pattern pattern = Pattern.compile("^\\s*select\\b.*\\bfrom\\s+\"?" + table + "\"?(?![a-z0-9_])",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
            return entries.stream().filter(e -> pattern.matcher(e.query()).find()).toList();
        }

        String describe() {
            final StringBuilder sb = new StringBuilder();
            for (String table : CHILD_TABLES) {
                sb.append(table).append(" calls=").append(calls(table)).append("; ");
            }
            sb.append(TRANSACTION_TABLE).append(" calls=").append(calls(TRANSACTION_TABLE)).append(" rows=").append(rows(TRANSACTION_TABLE))
                    .append(". Top statements: ");
            entries.stream().sorted(Comparator.comparingLong(Entry::calls).reversed()).limit(10)
                    .forEach(e -> sb.append("[calls=").append(e.calls()).append(" rows=").append(e.rows()).append("] ")
                            .append(e.query().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT)).append(" | "));
            return sb.toString();
        }
    }
}
