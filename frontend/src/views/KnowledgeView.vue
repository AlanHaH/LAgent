<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox, type UploadFile } from 'element-plus'
import { api } from '../api/http'

const spaces=ref<any[]>([]), selected=ref<any>(), documents=ref<any[]>([])
const newName=ref(''), creating=ref(false), uploading=ref(false)

async function load(){spaces.value=await api<any[]>({url:'/knowledge-spaces'});if(!selected.value&&spaces.value.length)await choose(spaces.value[0])}
async function choose(space:any){selected.value=space;documents.value=await api<any[]>({url:`/knowledge-spaces/${space.publicId}/documents`})}
onMounted(load)

async function create(){
  if(!newName.value.trim())return
  await api({method:'POST',url:'/knowledge-spaces',data:{name:newName.value.trim()}})
  newName.value='';creating.value=false;await load()
}
async function upload(file:UploadFile){
  if(!selected.value||!file.raw)return
  const data=new FormData();data.append('file',file.raw);uploading.value=true
  try{await api({method:'POST',url:`/knowledge-spaces/${selected.value.publicId}/documents`,data,headers:{'Content-Type':'multipart/form-data'}});ElMessage.success('文件已上传并完成安全检查、解析和索引');await choose(selected.value)}finally{uploading.value=false}
}
async function remove(doc:any){
  await ElMessageBox.confirm('删除会同步移除原文件、切块与向量索引，且不可恢复。','永久删除知识文档',{type:'warning'})
  const token=await api<any>({method:'POST',url:`/documents/${doc.publicId}/deletion-requests`})
  await api({method:'POST',url:`/documents/${doc.publicId}/deletion`,data:{token:token.token}})
  ElMessage.success('文档及索引已删除');await choose(selected.value)
}
function statusType(status:string){return status==='INDEXED'?'success':status?.includes('FAILED')?'danger':'warning'}
</script>

<template>
  <div>
    <div class="page-head"><div><h2>把资料变成可检索的知识</h2><p>文件经过安全校验、解析、切块和索引；问答引用会精确回到来源片段。</p></div><el-button type="primary" @click="creating=true">新建知识空间</el-button></div>
    <div class="grid kb-grid">
      <aside class="panel spaces"><h3>知识空间</h3><button v-for="space in spaces" :key="space.publicId" :class="{active:selected?.publicId===space.publicId}" @click="choose(space)"><span>▤</span><div><b>{{space.name}}</b><small>{{space.status||'ACTIVE'}}</small></div></button><div v-if="!spaces.length" class="empty">还没有知识空间</div></aside>
      <section class="panel">
        <div class="panel-title"><div><h3>{{selected?.name||'文档'}}</h3><p>支持 PDF、DOCX、Markdown、TXT，单文件大小受后端配置限制；视频和音频不进入学习闭环。</p></div><el-upload v-if="selected" :show-file-list="false" :auto-upload="false" :on-change="upload" accept=".pdf,.docx,.md,.txt"><el-button :loading="uploading">上传文档</el-button></el-upload></div>
        <el-table :data="documents">
          <el-table-column label="文档" min-width="250"><template #default="{row}"><b>{{row.displayName}}</b><div class="muted file-meta">版本 {{row.activeVersionNo}} · {{row.visibility}}</div></template></el-table-column>
          <el-table-column label="处理状态" width="140"><template #default="{row}"><el-tag :type="statusType(row.status)" effect="plain">{{row.status}}</el-tag></template></el-table-column>
          <el-table-column prop="createdAt" label="上传时间" width="190"/>
          <el-table-column label="操作" width="90"><template #default="{row}"><el-button link type="danger" @click="remove(row)">删除</el-button></template></el-table-column>
        </el-table>
        <div v-if="!documents.length" class="upload-empty">拖入学习资料，建立你的第一批可引用知识</div>
      </section>
    </div>
    <el-dialog v-model="creating" title="新建知识空间" width="420"><el-input v-model="newName" placeholder="例如：Java 后端课程" @keyup.enter="create"/><template #footer><el-button @click="creating=false">取消</el-button><el-button type="primary" @click="create">创建</el-button></template></el-dialog>
  </div>
</template>

<style scoped>.kb-grid{grid-template-columns:260px 1fr}.spaces{padding:13px}.spaces h3{padding:4px 10px}.spaces button{border:0;background:transparent;width:100%;display:flex;gap:12px;align-items:center;text-align:left;padding:12px;border-radius:9px}.spaces button.active{background:#e7efe9;color:var(--green)}.spaces button b,.spaces button small{display:block}.spaces button small{font-size:10px;color:var(--muted);margin-top:3px}.file-meta{font-size:10px;margin-top:5px}.upload-empty{border:1px dashed #cbd1ca;border-radius:12px;text-align:center;color:var(--muted);padding:50px;margin-top:20px}@media(max-width:800px){.kb-grid{grid-template-columns:1fr}}</style>
