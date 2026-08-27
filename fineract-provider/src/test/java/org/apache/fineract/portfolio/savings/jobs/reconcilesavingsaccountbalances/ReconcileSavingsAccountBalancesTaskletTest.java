/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.fineract.portfolio.savings.jobs.reconcilesavingsaccountbalances;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.scope.context.StepContext;

class ReconcileSavingsAccountBalancesTaskletTest {

    @Test
    void continuesAfterAccountFailureAndFailsAtTheEnd() {
        final SavingsAccountRepository repository = mock(SavingsAccountRepository.class);
        final ReconcileSavingsAccountBalanceProcessor processor = mock(ReconcileSavingsAccountBalanceProcessor.class);
        final ChunkContext chunkContext = mock(ChunkContext.class);
        final StepContext stepContext = mock(StepContext.class);
        when(chunkContext.getStepContext()).thenReturn(stepContext);
        when(stepContext.getJobParameters()).thenReturn(Map.of("batch-size", "3"));
        when(repository.findBalanceReconciliationCandidateIds(anyLong(), any(), any())).thenReturn(List.of(1L, 2L, 3L))
                .thenReturn(List.of());
        when(processor.reconcile(1L)).thenReturn(true);
        when(processor.reconcile(2L)).thenThrow(new IllegalStateException("account failed"));
        when(processor.reconcile(3L)).thenReturn(false);
        final ReconcileSavingsAccountBalancesTasklet tasklet = new ReconcileSavingsAccountBalancesTasklet(repository, processor);

        assertThatThrownBy(() -> tasklet.execute(mock(StepContribution.class), chunkContext)).isInstanceOf(IllegalStateException.class)
                .hasMessage("Savings balance reconciliation failed for 1 account(s).");

        verify(processor).reconcile(1L);
        verify(processor).reconcile(2L);
        verify(processor).reconcile(3L);
        verify(repository).findBalanceReconciliationCandidateIds(eq(3L), any(), any());
    }
}
