/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.fineract.portfolio.savings.jobs.reconcilesavingsaccountbalances;

import static org.apache.fineract.portfolio.savings.domain.SavingsAccountStatusType.ACTIVE;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountRepository;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class ReconcileSavingsAccountBalancesTasklet implements Tasklet {

    private final SavingsAccountRepository savingsAccountRepository;
    private final ReconcileSavingsAccountBalanceProcessor processor;

    @Override
    public RepeatStatus execute(final StepContribution contribution, final ChunkContext chunkContext) {
        final int batchSize = jobParameter(chunkContext, "batch-size");
        if (batchSize < 1) {
            throw new IllegalStateException("Reconcile Savings Account Balances requires a positive batch-size.");
        }

        long lastSavingsId = 0L;
        int reviewed = 0;
        int repaired = 0;
        int failed = 0;
        List<Long> savingsIds;
        do {
            savingsIds = this.savingsAccountRepository.findBalanceReconciliationCandidateIds(lastSavingsId, ACTIVE.getValue(),
                    PageRequest.of(0, batchSize));
            for (Long savingsId : savingsIds) {
                reviewed++;
                try {
                    if (this.processor.reconcile(savingsId)) {
                        repaired++;
                    }
                } catch (RuntimeException exception) {
                    failed++;
                    log.error("Failed to reconcile savings account {}", savingsId, exception);
                }
            }
            if (!savingsIds.isEmpty()) {
                lastSavingsId = savingsIds.get(savingsIds.size() - 1);
            }
        } while (savingsIds.size() == batchSize);

        log.info("Savings balance reconciliation finished: reviewed={}, repaired={}, unchanged={}, failed={}", reviewed, repaired,
                reviewed - repaired - failed, failed);
        if (failed > 0) {
            throw new IllegalStateException("Savings balance reconciliation failed for " + failed + " account(s).");
        }
        return RepeatStatus.FINISHED;
    }

    private int jobParameter(final ChunkContext chunkContext, final String name) {
        final Object value = chunkContext.getStepContext().getJobParameters().get(name);
        if (value == null) {
            throw new IllegalStateException("Missing required job parameter: " + name);
        }
        return Integer.parseInt(value.toString());
    }
}
