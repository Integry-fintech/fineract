# Desplegar la corrección de saldos con zero-interest pivot

Este runbook describe el paso a producción de la corrección que mantiene consistente el saldo derivado de las
cuentas de ahorro cuando `allow-backdated-transaction-before-interest-posting=false`. Incluye conciliación previa,
despliegue, validación de depósitos, retiros y transferencias Savings→Savings, desactivación controlada de `SA_RBAL`
y reversión.

> **Regla de seguridad:** no continuar si no se puede suspender temporalmente la escritura de depósitos, retiros,
> cargos, transferencias y reversos sobre cuentas de ahorro durante la conciliación y el despliegue.

## Resultado esperado

Al finalizar:

- el saldo derivado coincide con el ledger de transacciones activas;
- un cargo `PAY_CHARGE` (`transaction_type_enum=7`) reduce el saldo una sola vez;
- los cargos 4, 5 y 7 permanecen en sus totales correspondientes;
- crear y deshacer una transferencia restaura exactamente ambas cuentas, excepto por cargos independientes;
- una ejecución posterior de `SA_RBAL` termina con `repaired=0` y `failed=0`;
- `SA_RBAL` queda desactivado y `SA_ZIPV` conserva su configuración anterior.

## Ruta rápida

1. Registrar versión, configuraciones, jobs y backup.
2. Suspender escrituras monetarias de cuentas de ahorro.
3. Ejecutar `SA_RBAL` en la versión actual y exigir `failed=0`.
4. Confirmar mediante SQL que no existen diferencias contra el ledger.
5. Desplegar el nuevo artefacto sin modificar `SA_ZIPV` ni las configuraciones globales.
6. Ejecutar el smoke test de cargo tipo 7, transferencia, `undo`, depósito y retiro.
7. Ejecutar nuevamente `SA_RBAL`; exigir `repaired=0` y `failed=0`.
8. Desactivar solo `SA_RBAL`, reanudar el tráfico gradualmente y observar.

## Datos del cambio

Completar antes de iniciar:

| Dato | Valor |
|---|---|
| Versión o tag | `<VERSION>` |
| Digest de imagen | `<IMAGE_DIGEST>` |
| Commit | `<GIT_SHA>` |
| Tenant(s) | `<TENANTS>` |
| Inicio de ventana | `<TIMESTAMP>` |
| Responsable del despliegue | `<NOMBRE>` |
| Responsable de validación | `<NOMBRE>` |
| Versión anterior para rollback | `<PREVIOUS_VERSION>` |

## Alcance técnico

El cambio:

- reconstruye el saldo como `saldo del pivot + créditos activos - débitos activos` posteriores al pivot;
- conserva los totales históricos en lugar de reemplazarlos con un fragmento post-pivot;
- aplica y revierte simétricamente depósitos, retiros, cargos, interés, sobregiro y retención;
- rechaza un segundo `undo` sobre una transacción ya reversada;
- mantiene sin cambios el comportamiento de pivots normales de publicación de interés;
- no modifica el contrato REST, el esquema de base de datos ni la orquestación de `/v1/accounttransfers`.

## Variables para comandos API

Los ejemplos suponen que `FINERACT_API_BASE` termina en `/fineract-provider/api/v1`.

```bash
export FINERACT_API_BASE='https://<host>/fineract-provider/api/v1'
export FINERACT_TENANT='default'
export FINERACT_USER='<usuario-operativo>'
export FINERACT_PASSWORD='<secreto>'
```

No guardar las credenciales en el historial de la terminal ni en el repositorio. Los comandos deben ejecutarse con
un usuario autorizado para consultar configuraciones y administrar jobs.

## 1. Verificaciones previas

### 1.1 Validar el artefacto

- [ ] La versión, el commit y el digest corresponden al artefacto aprobado.
- [ ] Pasaron las pruebas de dominio de ahorros.
- [ ] Pasaron las pruebas de `SA_RBAL`.
- [ ] Pasó `AccountTransferWithdrawalFeeTest` contra PostgreSQL.
- [ ] Existe una versión anterior disponible para rollback inmediato.

Evidencia esperada para esta corrección:

```text
SavingsAccountBalanceReconciliationTest: 7 pruebas exitosas
ReconcileSavingsAccountBalances: 3 pruebas exitosas
AccountTransferWithdrawalFeeTest: 3 pruebas exitosas
```

### 1.2 Respaldar y preparar la ventana

- [ ] Crear un backup o snapshot verificable de cada base de datos tenant.
- [ ] Confirmar que el backup puede restaurarse y registrar su identificador.
- [ ] Suspender procesos externos que creen depósitos, retiros, cargos, transferencias o reversos.
- [ ] Drenar las solicitudes en curso antes de ejecutar la conciliación final.
- [ ] Confirmar que no hay otro despliegue o migración ejecutándose.

### 1.3 Registrar configuraciones actuales

Consultar y guardar las respuestas:

```bash
curl -fsS -u "$FINERACT_USER:$FINERACT_PASSWORD" \
  -H "Fineract-Platform-TenantId: $FINERACT_TENANT" \
  "$FINERACT_API_BASE/configurations/name/allow-backdated-transaction-before-interest-posting"

curl -fsS -u "$FINERACT_USER:$FINERACT_PASSWORD" \
  -H "Fineract-Platform-TenantId: $FINERACT_TENANT" \
  "$FINERACT_API_BASE/configurations/name/allow-backdated-transaction-before-interest-posting-date-for-days"
```

Valores requeridos para el escenario corregido:

| Configuración | Valor |
|---|---|
| `allow-backdated-transaction-before-interest-posting` | `enabled=false` |
| `allow-backdated-transaction-before-interest-posting-date-for-days` | `enabled=false` |

No cambiar estos valores durante la ventana. Si difieren, detener el procedimiento y revisar el alcance.

### 1.4 Registrar los jobs

En cada tenant, consultar la configuración vigente:

```sql
SELECT j.id,
       j.short_name,
       j.is_active,
       j.cron_expression,
       j.currently_running,
       jp.parameter_name,
       jp.parameter_value
FROM job j
LEFT JOIN job_parameters jp ON jp.job_id = j.id
WHERE j.short_name IN ('SA_RBAL', 'SA_ZIPV')
ORDER BY j.short_name, jp.parameter_name;
```

Condiciones para continuar:

- [ ] Ambos jobs existen.
- [ ] `SA_RBAL.batch-size` es mayor que cero.
- [ ] Ninguno está ejecutándose.
- [ ] Se registró la programación de cinco minutos que utiliza actualmente `SA_RBAL`.
- [ ] La configuración de `SA_ZIPV` quedó documentada y no será modificada.

## 2. Conciliación final antes del despliegue

Ejecutar `SA_RBAL` manualmente con las escrituras de ahorros suspendidas:

```bash
curl -fsS -X POST -u "$FINERACT_USER:$FINERACT_PASSWORD" \
  -H "Fineract-Platform-TenantId: $FINERACT_TENANT" \
  -H 'Content-Type: application/json' \
  "$FINERACT_API_BASE/jobs/shortName/SA_RBAL?command=executeJob" \
  -d '{}'
```

La respuesta confirma únicamente que la ejecución fue aceptada. Esperar a que finalice y verificar su run history:

```sql
SELECT h.id,
       h.start_time,
       h.end_time,
       h.status,
       h.trigger_type,
       h.error_message
FROM job_run_history h
JOIN job j ON j.id = h.job_id
WHERE j.short_name = 'SA_RBAL'
ORDER BY h.id DESC
LIMIT 1;
```

Revisar además el log de aplicación:

```text
Savings balance reconciliation finished: reviewed=<N>, repaired=<N>, unchanged=<N>, failed=0
```

En esta ejecución previa se permite `repaired>0`, porque su objetivo es corregir inconsistencias existentes. No
continuar si el estado no es `success`, si `failed>0` o si falta el mensaje final.

### 2.1 Comparar saldo derivado contra el ledger

Ejecutar la siguiente consulta de solo lectura en cada base tenant:

```sql
WITH ledger AS (
    SELECT sat.savings_account_id,
           SUM(
               CASE
                   WHEN sat.transaction_type_enum IN (1, 3, 8) THEN sat.amount
                   WHEN sat.transaction_type_enum IN (2, 4, 5, 7, 17, 18) THEN -sat.amount
                   ELSE 0
               END
           ) AS ledger_balance
    FROM m_savings_account_transaction sat
    WHERE sat.is_reversed = false
      AND sat.is_reversal = false
    GROUP BY sat.savings_account_id
)
SELECT sa.id AS savings_account_id,
       sa.account_balance_derived,
       COALESCE(l.ledger_balance, 0) AS ledger_balance,
       sa.account_balance_derived - COALESCE(l.ledger_balance, 0) AS difference
FROM m_savings_account sa
LEFT JOIN ledger l ON l.savings_account_id = sa.id
WHERE EXISTS (
    SELECT 1
    FROM m_savings_account_transaction pivot
    WHERE pivot.savings_account_id = sa.id
      AND pivot.is_zero_interest_pivot = true
      AND pivot.is_reversed = false
      AND pivot.is_reversal = false
)
  AND (
      sa.account_balance_derived IS NULL
      OR ROUND(sa.account_balance_derived - COALESCE(l.ledger_balance, 0), 6) <> 0
  )
ORDER BY sa.id;
```

**Criterio obligatorio:** la consulta debe devolver cero filas. Guardar el resultado como evidencia.

## 3. Desplegar

1. Mantener suspendidas las escrituras monetarias de cuentas de ahorro.
2. Desplegar exactamente el digest registrado en la sección **Datos del cambio**.
3. Esperar a que todas las instancias anteriores hayan salido de rotación.
4. Confirmar que todas las instancias nuevas reportan la misma versión y digest.
5. Validar health checks, conexión a PostgreSQL y ausencia de errores de Liquibase o JPA.
6. Confirmar nuevamente las dos configuraciones globales de la sección 1.3.
7. No ejecutar manualmente ni cambiar la programación de `SA_ZIPV`.

### Puerta de salida

Detener el despliegue y ejecutar rollback si ocurre cualquiera de estas condiciones:

- una instancia no inicia o reporta una versión diferente;
- aparece un error de base de datos, Liquibase, resumen de ahorros o journal entries;
- alguna configuración cambia inesperadamente;
- se reanudan escrituras antes de terminar el smoke test.

## 4. Smoke test financiero controlado

Usar dos cuentas internas aprobadas, en la misma moneda, con producto de ahorro de interés cero y zero-interest
pivot existente. Registrar los IDs y todos los valores antes de comenzar.

> No usar cuentas de clientes ni montos reales no autorizados.

### 4.1 Estado inicial

Para ambas cuentas registrar:

- `accountBalance`;
- `totalDeposits`;
- `totalWithdrawals`;
- `totalWithdrawalFees`;
- `totalAnnualFees`;
- `totalFeeCharge`;
- última transacción y su `runningBalance`.

### 4.2 Cargo tipo 7

1. Aplicar un cargo controlado `PAY_CHARGE` sobre la cuenta origen.
2. Confirmar que el saldo disminuye exactamente por el cargo.
3. Confirmar que `totalFeeCharge` aumenta exactamente por el mismo monto.
4. Confirmar que `totalWithdrawalFees` y `totalAnnualFees` no cambian.

### 4.3 Transferencia Savings→Savings

1. Crear la transferencia mediante `POST /v1/accounttransfers`.
2. Confirmar en la cuenta origen:
   - disminución por el monto transferido;
   - disminución adicional por el cargo de retiro, si corresponde;
   - incremento de `totalWithdrawals` por el monto transferido;
   - incremento de `totalWithdrawalFees` solamente por el cargo tipo 4.
3. Confirmar en la cuenta destino:
   - incremento exacto del saldo;
   - incremento exacto de `totalDeposits`.
4. Confirmar que la suma de ambos saldos solo disminuyó por los cargos aplicados.

### 4.4 Deshacer la transferencia

1. Ejecutar `POST /v1/accounttransfers/{id}?command=undo`.
2. Confirmar que origen y destino regresan a los valores anteriores a la transferencia.
3. Confirmar que el cargo tipo 7 independiente permanece aplicado.
4. Confirmar que el cargo de retiro propio de la transferencia fue revertido.
5. No repetir el `undo`. Si se prueba deliberadamente, el segundo intento debe ser rechazado y no debe cambiar
   saldos ni cargos.

### 4.5 Operaciones posteriores

1. Ejecutar un depósito controlado.
2. Ejecutar un retiro controlado.
3. Confirmar inmediatamente saldos y totales, antes de ejecutar `SA_RBAL`.

## 5. Conciliación posterior al despliegue

Ejecutar nuevamente `SA_RBAL` y esperar su finalización como se describe en la sección 2.

Esta vez el criterio es estricto:

```text
failed=0
repaired=0
```

También debe cumplirse:

- [ ] La consulta de diferencias devuelve cero filas.
- [ ] Los saldos y totales del smoke test no cambian después del job.
- [ ] No se generan transacciones ni journal entries adicionales por la conciliación.

Si `repaired>0`, no desactivar el job ni reanudar tráfico. Capturar los IDs afectados, conservar los logs y pasar a
la sección de rollback.

## 6. Desactivar `SA_RBAL`

Desactivar únicamente el job `SA_RBAL`. No detener el scheduler global, porque eso afectaría todos los jobs.

Obtener su ID desde la consulta de la sección 1.4 y ejecutar:

```bash
export SA_RBAL_JOB_ID='<job-id>'

curl -fsS -X PUT -u "$FINERACT_USER:$FINERACT_PASSWORD" \
  -H "Fineract-Platform-TenantId: $FINERACT_TENANT" \
  -H 'Content-Type: application/json' \
  "$FINERACT_API_BASE/jobs/$SA_RBAL_JOB_ID" \
  -d '{"active":false}'
```

Confirmar en base de datos:

```sql
SELECT id, short_name, is_active, cron_expression, currently_running
FROM job
WHERE short_name IN ('SA_RBAL', 'SA_ZIPV')
ORDER BY short_name;
```

Criterios:

- `SA_RBAL.is_active=false`;
- `SA_RBAL.currently_running=false`;
- `SA_ZIPV` conserva exactamente `is_active` y `cron_expression` previos.

## 7. Reanudar tráfico y observar

1. Reanudar gradualmente depósitos, retiros, cargos, transferencias y reversos.
2. Vigilar errores de saldo insuficiente, transferencias, journal entries y reversos.
3. Ejecutar periódicamente la consulta de diferencias de la sección 2.1 durante la observación.
4. Confirmar que no existen cuentas con diferencias ni nuevos saldos negativos inesperados.
5. Cerrar la ventana solamente después de registrar toda la evidencia.

## 8. Rollback

Ejecutar rollback si:

- la consulta de diferencias devuelve filas;
- el smoke test no respeta los valores esperados;
- `SA_RBAL` termina con `failed>0` o repara cuentas después del despliegue;
- una transferencia o su `undo` deja origen o destino descuadrados;
- aparecen errores contables o saldos negativos no explicados.

Procedimiento:

1. Suspender nuevamente todas las escrituras monetarias de ahorros.
2. Reactivar la programación anterior de `SA_RBAL`:

   ```bash
   curl -fsS -X PUT -u "$FINERACT_USER:$FINERACT_PASSWORD" \
     -H "Fineract-Platform-TenantId: $FINERACT_TENANT" \
     -H 'Content-Type: application/json' \
     "$FINERACT_API_BASE/jobs/$SA_RBAL_JOB_ID" \
     -d '{"active":true}'
   ```

3. Revertir todas las instancias a `<PREVIOUS_VERSION>`.
4. Confirmar health checks y versión desplegada.
5. Ejecutar `SA_RBAL` manualmente y exigir `failed=0`.
6. Ejecutar la consulta de diferencias y exigir cero filas.
7. Mantener `SA_RBAL` activo cada cinco minutos hasta completar el análisis.
8. No revertir transacciones manualmente ni modificar saldos directamente por SQL.
9. Restaurar el backup únicamente mediante el procedimiento de recuperación aprobado y solo si el rollback de
   aplicación más la conciliación no restablecen la consistencia.

## 9. Evidencia de cierre

Adjuntar al ticket de cambio:

- [ ] versión, commit y digest desplegados;
- [ ] identificador del backup;
- [ ] configuraciones globales antes y después;
- [ ] configuración de `SA_RBAL` y `SA_ZIPV` antes y después;
- [ ] run history de `SA_RBAL` previo y posterior;
- [ ] líneas de log con `reviewed`, `repaired`, `unchanged` y `failed`;
- [ ] resultado vacío de la consulta de diferencias;
- [ ] IDs y resultados del smoke test;
- [ ] hora de reanudación del tráfico;
- [ ] decisión final de cierre o rollback.

## Referencias

- [Postmortem de la inconsistencia de saldos](docs/incidents/2026-08-25-savings-balance-recalculation-postmortem.md)
- Prueba de dominio: `fineract-savings/src/test/java/org/apache/fineract/portfolio/savings/domain/SavingsAccountBalanceReconciliationTest.java`
- Prueba de transferencia: `integration-tests/src/test/java/org/apache/fineract/integrationtests/savings/AccountTransferWithdrawalFeeTest.java`
