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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW;

import java.time.LocalDate;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountAssembler;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountRepositoryWrapper;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountTransactionRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

class PostZeroInterestPivotProcessorTransactionTest {

    @Test
    void startsAndCommitsANewTransactionForEachAccount() {
        final SavingsAccountAssembler assembler = mock(SavingsAccountAssembler.class);
        final SavingsAccount firstAccount = mock(SavingsAccount.class);
        final SavingsAccount secondAccount = mock(SavingsAccount.class);
        when(assembler.assembleForZeroInterestPivot(1L)).thenReturn(firstAccount);
        when(assembler.assembleForZeroInterestPivot(2L)).thenReturn(secondAccount);
        final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        final TransactionStatus firstStatus = mock(TransactionStatus.class);
        final TransactionStatus secondStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(firstStatus, secondStatus);
        final PostZeroInterestPivotProcessor processor = transactionalProxy(assembler, transactionManager);

        assertThat(processor.postPivot(1L, LocalDate.of(2026, 8, 12))).isFalse();
        assertThat(processor.postPivot(2L, LocalDate.of(2026, 8, 12))).isFalse();

        final ArgumentCaptor<TransactionDefinition> definitions = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactionManager, times(2)).getTransaction(definitions.capture());
        assertThat(definitions.getAllValues()).allMatch(definition -> definition.getPropagationBehavior() == PROPAGATION_REQUIRES_NEW);
        verify(transactionManager).commit(firstStatus);
        verify(transactionManager).commit(secondStatus);
    }

    @Test
    void rollsBackTheAccountTransactionWhenProcessingFails() {
        final SavingsAccountAssembler assembler = mock(SavingsAccountAssembler.class);
        when(assembler.assembleForZeroInterestPivot(1L)).thenThrow(new IllegalStateException("account failed"));
        final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        final TransactionStatus status = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(status);
        final PostZeroInterestPivotProcessor processor = transactionalProxy(assembler, transactionManager);

        assertThatThrownBy(() -> processor.postPivot(1L, LocalDate.of(2026, 8, 12))).isInstanceOf(IllegalStateException.class)
                .hasMessage("account failed");

        verify(transactionManager).rollback(status);
    }

    private PostZeroInterestPivotProcessor transactionalProxy(final SavingsAccountAssembler assembler,
            final PlatformTransactionManager transactionManager) {
        final PostZeroInterestPivotProcessor target = new PostZeroInterestPivotProcessor(assembler,
                mock(SavingsAccountRepositoryWrapper.class), mock(SavingsAccountTransactionRepository.class));
        final TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(transactionManager);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        final ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.addAdvice(interceptor);
        return (PostZeroInterestPivotProcessor) proxyFactory.getProxy();
    }
}
