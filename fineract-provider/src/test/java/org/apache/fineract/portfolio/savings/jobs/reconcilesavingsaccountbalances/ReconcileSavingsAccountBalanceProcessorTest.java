/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.fineract.portfolio.savings.jobs.reconcilesavingsaccountbalances;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountAssembler;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class ReconcileSavingsAccountBalanceProcessorTest {

    @Test
    void reconcilesTheLockedAccountSummary() {
        final SavingsAccountAssembler assembler = mock(SavingsAccountAssembler.class);
        final SavingsAccount account = mock(SavingsAccount.class);
        when(assembler.assembleForBalanceReconciliation(9L)).thenReturn(account);
        when(account.reconcileTransactionDerivedSummary()).thenReturn(true);
        final ReconcileSavingsAccountBalanceProcessor processor = new ReconcileSavingsAccountBalanceProcessor(assembler);

        assertThat(processor.reconcile(9L)).isTrue();

        verify(account).reconcileTransactionDerivedSummary();
    }

    @Test
    void startsANewTransactionForEveryAccount() throws NoSuchMethodException {
        final Transactional transactional = ReconcileSavingsAccountBalanceProcessor.class.getMethod("reconcile", Long.class)
                .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }
}
