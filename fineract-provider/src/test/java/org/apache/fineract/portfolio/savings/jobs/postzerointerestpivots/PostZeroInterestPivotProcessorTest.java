/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.fineract.portfolio.savings.jobs.postzerointerestpivots;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Collections;
import org.apache.fineract.portfolio.savings.DepositAccountType;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountAssembler;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountRepositoryWrapper;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransaction;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransactionRepository;
import org.junit.jupiter.api.Test;

class PostZeroInterestPivotProcessorTest {

    @Test
    void createsOnlyTheTechnicalSavingsTransactionForAnEligibleAccount() {
        final SavingsAccountAssembler assembler = mock(SavingsAccountAssembler.class);
        final SavingsAccountRepositoryWrapper accountRepository = mock(SavingsAccountRepositoryWrapper.class);
        final SavingsAccountTransactionRepository transactionRepository = mock(SavingsAccountTransactionRepository.class);
        final SavingsAccount account = mock(SavingsAccount.class);
        final SavingsAccountTransaction pivot = mock(SavingsAccountTransaction.class);
        final LocalDate cutOffDate = LocalDate.of(2026, 8, 12);
        when(assembler.assembleForZeroInterestPivot(9L)).thenReturn(account);
        when(account.isActive()).thenReturn(true);
        when(account.depositAccountType()).thenReturn(DepositAccountType.SAVINGS_DEPOSIT);
        when(account.getSavingsAccountTransactionsWithPivotConfig()).thenReturn(Collections.emptyList());
        when(accountRepository.findZeroInterestPivots(any(), any())).thenReturn(Collections.emptyList());
        when(account.postZeroInterestPivot(cutOffDate)).thenReturn(pivot);

        final PostZeroInterestPivotProcessor processor = new PostZeroInterestPivotProcessor(assembler, accountRepository,
                transactionRepository);

        assertThat(processor.postPivot(9L, cutOffDate)).isTrue();
        verify(transactionRepository).saveAndFlush(pivot);
        verify(accountRepository).saveAndFlush(account);
    }

    @Test
    void skipsAnIneligibleAccountWithoutWritingAnything() {
        final SavingsAccountAssembler assembler = mock(SavingsAccountAssembler.class);
        final SavingsAccountRepositoryWrapper accountRepository = mock(SavingsAccountRepositoryWrapper.class);
        final SavingsAccountTransactionRepository transactionRepository = mock(SavingsAccountTransactionRepository.class);
        final SavingsAccount account = mock(SavingsAccount.class);
        when(assembler.assembleForZeroInterestPivot(9L)).thenReturn(account);
        when(account.isActive()).thenReturn(false);

        final PostZeroInterestPivotProcessor processor = new PostZeroInterestPivotProcessor(assembler, accountRepository,
                transactionRepository);

        assertThat(processor.postPivot(9L, LocalDate.of(2026, 8, 12))).isFalse();
        verify(transactionRepository, never()).saveAndFlush(any());
        verify(accountRepository, never()).saveAndFlush(any());
    }
}
