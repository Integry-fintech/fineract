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

import static org.apache.fineract.portfolio.savings.domain.SavingsAccountStatusType.ACTIVE;

import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.portfolio.savings.DepositAccountType;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountRepository;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/** Posts daily technical checkpoints for active savings accounts with no interest. */
@Component
@Slf4j
@RequiredArgsConstructor
public class PostZeroInterestPivotsForSavingsTasklet implements Tasklet {

    private final ConfigurationDomainService configurationDomainService;
    private final SavingsAccountRepository savingsAccountRepository;
    private final PostZeroInterestPivotProcessor pivotProcessor;

    @Override
    public RepeatStatus execute(final StepContribution contribution, final ChunkContext chunkContext) {
        if (!this.configurationDomainService.retrievePivotDateConfig()
                || this.configurationDomainService.isRelaxingDaysConfigForPivotDateEnabled()) {
            throw new IllegalStateException("Post Zero Interest Pivots For Savings requires strict interest-posting pivot "
                    + "configuration: backdated transactions must be disabled and relaxed pivot days must be disabled.");
        }

        final int threadPoolSize = jobParameter(chunkContext, "thread-pool-size");
        final int batchSize = jobParameter(chunkContext, "batch-size");
        if (threadPoolSize < 1 || batchSize < 1) {
            throw new IllegalStateException("Post Zero Interest Pivots For Savings requires positive thread-pool-size and batch-size.");
        }

        final LocalDate cutOffDate = DateUtils.getBusinessLocalDate().minusDays(1);
        long lastSavingsId = 0L;
        int created = 0;
        List<Long> savingsIds;
        do {
            savingsIds = this.savingsAccountRepository.findZeroInterestPivotCandidateIds(lastSavingsId, ACTIVE.getValue(),
                    DepositAccountType.SAVINGS_DEPOSIT.getValue(), PageRequest.of(0, batchSize));
            for (Long savingsId : savingsIds) {
                if (this.pivotProcessor.postPivot(savingsId, cutOffDate)) {
                    created++;
                }
            }
            if (!savingsIds.isEmpty()) {
                lastSavingsId = savingsIds.get(savingsIds.size() - 1);
            }
        } while (savingsIds.size() == batchSize);

        // The standard parameter is retained for scheduler compatibility. Account
        // locking makes the per-account writes intentionally sequential here.
        log.info("Created {} zero-interest pivots for {} (thread-pool-size={})", created, cutOffDate, threadPoolSize);
        return RepeatStatus.FINISHED;
    }

    private int jobParameter(final ChunkContext chunkContext, final String name) {
        return Integer.parseInt((String) chunkContext.getStepContext().getJobParameters().get(name));
    }
}
