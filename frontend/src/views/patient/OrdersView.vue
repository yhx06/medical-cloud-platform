<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { listPatientOrders, payPatientOrder } from '../../api/orders'
import type { MedicalOrder } from '../../types/business'
import { money, useWorkflow } from '../../composables/useWorkflow'
import AsyncState from '../../components/AsyncState.vue'
import StatusTag from '../../components/StatusTag.vue'
import WorkflowFeedback from '../../components/WorkflowFeedback.vue'
const items = ref<MedicalOrder[]>([])
const { loading, busy, error, message, load, perform } = useWorkflow()
const refresh = () => load(async () => { items.value = await listPatientOrders() })
async function pay(item: MedicalOrder) {
  if (!window.confirm('确认模拟支付 ' + money(item.amount) + '？此操作不产生真实扣款。')) return
  await perform(async () => { const updated = await payPatientOrder(item.id); items.value = items.value.map(row => row.id === item.id ? updated : row) }, '支付结果已确认')
}
onMounted(refresh)
</script>
<template>
  <section class="workspace"><header><p class="eyebrow">PATIENT / ORDERS</p><h2>我的订单</h2><p class="muted">课程演示模拟支付。重复请求返回同一支付结果；支付结果以服务端流水为准。</p></header>
    <button :disabled="loading || busy" @click="refresh">刷新订单</button>
    <WorkflowFeedback :error="error" :message="message" />
    <AsyncState :loading="loading" :empty="!items.length" empty-text="暂无订单，医生完成接诊后自动生成">
      <div class="table-scroll"><table><thead><tr><th>订单 / 预约</th><th>金额</th><th>状态</th><th>支付流水 / 时间</th><th>操作</th></tr></thead><tbody>
        <tr v-for="item in items" :key="item.id"><td>{{ item.id }}<small>{{ item.appointmentId }}</small></td><td>{{ money(item.amount) }}</td><td><StatusTag :status="item.status" /></td><td>{{ item.transactionNo || '—' }}<small>{{ item.paidAt || '尚未支付' }}</small></td><td><button v-if="item.status === 'PENDING'" data-test="pay-order" :disabled="busy" @click="pay(item)">模拟支付</button></td></tr>
      </tbody></table></div>
    </AsyncState>
  </section>
</template>
