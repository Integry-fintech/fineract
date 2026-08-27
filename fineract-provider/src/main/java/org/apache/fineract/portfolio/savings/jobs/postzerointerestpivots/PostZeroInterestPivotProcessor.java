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
package org.apache.fineract.portfolio.savings.jobs.postzerointerestpivots;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.portfolio.savings.DepositAccountType;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountAssembler;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountRepositoryWrapper;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransactionRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Creates one non-financial zero-interest checkpoint while holding the account lock. */
@Service
@Slf4j
@RequiredArgsConstructor
public class PostZeroInterestPivotProcessor {

    private final SavingsAccountAssembler savingsAccountAssembler;
    private final SavingsAccountRepositoryWrapper savingsAccountRepository;
    private final SavingsAccountTransactionRepository savingsAccountTransactionRepository;

    /**
     * @return {@code true} when a checkpoint was created; {@code false} when the account is not eligible or must be
     *         retried on the next cycle.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean postPivot(final Long savingsId, final LocalDate cutOffDate) {
        final SavingsAccount account = this.savingsAccountAssembler.assembleForZeroInterestPivot(savingsId);
        if (!isEligible(account)) {
            return false;
        }
        final List<SavingsAccountTransaction> transactions = account.getSavingsAccountTransactionsWithPivotConfig();
        if (hasActivityAfter(transactions, cutOffDate)) {
            log.info("Skipping zero-interest pivot for savings account {} on {} because it has later activity; it will be retried.",
                    savingsId, cutOffDate);
            return false;
        }
        if (hasInterestPostingOn(transactions, cutOffDate) || hasZeroInterestPivotOn(account, cutOffDate)) {
            return false;
        }

        final SavingsAccountTransaction pivot = account.postZeroInterestPivot(cutOffDate);
        // Saving the transaction directly avoids any accounting integration. The
        // per-account transaction commits it together with the managed account summary.
        this.savingsAccountTransactionRepository.save(pivot);
        log.debug("Created zero-interest pivot for savings account {} on {}", savingsId, cutOffDate);
        return true;
    }

    private boolean isEligible(final SavingsAccount account) {
        return account.isActive() && account.depositAccountType() == DepositAccountType.SAVINGS_DEPOSIT
                && isZero(account.getNominalAnnualInterestRate()) && isZero(account.getNominalAnnualInterestRateOverdraft());
    }

    private boolean isZero(final BigDecimal interestRate) {
        return interestRate == null || interestRate.compareTo(BigDecimal.ZERO) == 0;
    }

    private boolean hasActivityAfter(final List<SavingsAccountTransaction> transactions, final LocalDate cutOffDate) {
        return transactions.stream().anyMatch(transaction -> DateUtils.isAfter(transaction.getTransactionDate(), cutOffDate));
    }

    private boolean hasInterestPostingOn(final List<SavingsAccountTransaction> transactions, final LocalDate cutOffDate) {
        return transactions.stream().anyMatch(transaction -> transaction.isInterestPostingAndNotReversed()
                && !transaction.isReversalTransaction() && transaction.occursOn(cutOffDate));
    }

    private boolean hasZeroInterestPivotOn(final SavingsAccount account, final LocalDate cutOffDate) {
        return this.savingsAccountRepository.findZeroInterestPivots(account, PageRequest.of(0, 1)).stream()
                .anyMatch(transaction -> transaction.occursOn(cutOffDate));
    }
}
