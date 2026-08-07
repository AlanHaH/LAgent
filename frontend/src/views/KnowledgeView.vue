<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox, type UploadFile } from 'element-plus'
import { api } from '../api/http'
import BooksView from './BooksView.vue'

// 知识库页内 Tab：资料 | 图书（图书接入微信读书）
const activeTab = ref<'materials' | 'books'>('materials')
const spaces=ref<any[]>([]), selected=ref<any>(), documents=ref<any[]>([]), categories=ref<any[]>([])
const newName=ref(''), creating=ref(false), uploading=ref(false)
const editing=ref(false), editName=ref(''), editingSpace=ref<any>()
const categoryDialog=ref(false), categoryName=ref(''), categoryParent=ref('')
const detailVisible=ref(false), documentDetail=ref<any>()
const contentVisible=ref(false), contentDoc=ref<any>(), contentChunks=ref<any[]>([]), contentLoading=ref(false)
const documentPolls=new Map<string,ReturnType<typeof setTimeout>>()
function chunkTitle(chunk:any){
  try{
    const titlePath=JSON.parse(chunk.titlePathJson||'[]')
    if(Array.isArray(titlePath)&&titlePath.length)return titlePath[titlePath.length-1]
  }catch{/* 标题缺失时忽略 */}
  return ''
}
async function openContent(doc:any){
  contentDoc.value=doc
  contentChunks.value=[]
  contentVisible.value=true
  contentLoading.value=true
  try{contentChunks.value=await api<any[]>({url:`/documents/${doc.publicId}/content`})}
  catch(error){ElMessage.error(errorMessage(error))}
  finally{contentLoading.value=false}
}
function errorMessage(error:any){return error?.response?.data?.error?.message||error?.response?.data?.message||error?.message||'加载失败'}
const categoryFilter=ref('')
const filteredDocuments=computed(()=>{
  if(!categoryFilter.value)return documents.value
  if(categoryFilter.value==='UNCATEGORIZED')return documents.value.filter((d:any)=>!d.categoryPublicId)
  return documents.value.filter((d:any)=>d.categoryPublicId===categoryFilter.value)
})
// 全部资料模式：跨空间汇总，支持按空间/文档名关键词 + 空间+分类组合筛选
const allMode=ref(false), allDocuments=ref<any[]>([]), allCategories=ref<any[]>([])
const keyword=ref(''), allCategoryFilter=ref('')
const filteredAll=computed(()=>{
  let list=allDocuments.value
  const kw=keyword.value.trim().toLowerCase()
  if(kw)list=list.filter((d:any)=>(d.displayName||'').toLowerCase().includes(kw)||(d.spaceName||'').toLowerCase().includes(kw))
  if(allCategoryFilter.value==='UNCATEGORIZED')list=list.filter((d:any)=>!d.categoryPublicId)
  else if(allCategoryFilter.value){
    const[sp,cat]=allCategoryFilter.value.split('__')
    list=list.filter((d:any)=>d.spacePublicId===sp&&d.categoryPublicId===cat)
  }
  return list
})
// 后端根级分类的 path 以 "/" 开头（如 "/教材"），显示时去掉前导斜杠
function displayPath(c:any){return String(c.path||'').replace(/^\/+/, '')}
function categoriesForDoc(d:any){
  if(!allMode.value)return categories.value
  return allCategories.value.filter((c:any)=>c.spacePublicId===d.spacePublicId)
}
async function loadAll(){
  if(!spaces.value.length)return
  const lists=await Promise.all(spaces.value.map(s=>api<any[]>({url:`/knowledge-spaces/${s.publicId}/documents`}).catch(()=>[])))
  allDocuments.value=lists.flatMap((docs,i)=>docs.map(d=>({...d,spaceName:spaces.value[i].name,spacePublicId:spaces.value[i].publicId})))
  allCategories.value=(await Promise.all(spaces.value.map(async s=>{
    const cats=await api<any[]>({url:`/knowledge-spaces/${s.publicId}/categories`}).catch(()=>[])
    return cats.map(c=>({spacePublicId:s.publicId,spaceName:s.name,id:c.id,path:c.path}))
  }))).flat()
}
watch(allMode,v=>{if(v){keyword.value='';allCategoryFilter.value='';loadAll()}else categoryFilter.value=''})
async function reloadCurrent(){if(allMode.value)await loadAll();else if(selected.value)await choose(selected.value)}

async function load(){spaces.value=await api<any[]>({url:'/knowledge-spaces'});if(!selected.value&&spaces.value.length)await choose(spaces.value[0])}
async function choose(space:any){
  if(selected.value?.publicId!==space.publicId)categoryFilter.value=''
  allMode.value=false
  selected.value=space
  ;[documents.value,categories.value]=await Promise.all([
    api<any[]>({url:`/knowledge-spaces/${space.publicId}/documents`}),
    api<any[]>({url:`/knowledge-spaces/${space.publicId}/categories`}),
  ])
}
onMounted(load)
onUnmounted(()=>{documentPolls.forEach(timer=>clearTimeout(timer));documentPolls.clear()})

function stopDocumentPolling(documentId:string){
  const timer=documentPolls.get(documentId)
  if(timer)clearTimeout(timer)
  documentPolls.delete(documentId)
}
function startDocumentPolling(documentId:string,attempt=0){
  stopDocumentPolling(documentId)
  if(attempt>=90)return
  const timer=setTimeout(async()=>{
    try{
      const detail=await api<any>({url:`/documents/${documentId}`,silent:true})
      const status=detail.document?.status||detail.status
      await reloadCurrent()
      if(status==='INDEXED'){stopDocumentPolling(documentId);ElMessage.success('文件解析和索引已完成');return}
      if(String(status).includes('FAILED')){stopDocumentPolling(documentId);ElMessage.error('文件处理失败，请打开详情查看原因');return}
      startDocumentPolling(documentId,attempt+1)
    }catch{stopDocumentPolling(documentId)}
  },2000)
  documentPolls.set(documentId,timer)
}

async function create(){
  if(!newName.value.trim())return
  await api({method:'POST',url:'/knowledge-spaces',data:{name:newName.value.trim()}})
  newName.value='';creating.value=false;await load()
}
async function upload(file:UploadFile){
  if(!selected.value||!file.raw)return
  const size=file.raw.size
  if(size>200*1024*1024){ElMessage.error('单文件不能超过 200 MB');return}
  if(size>50*1024*1024)ElMessage.warning('文件较大，上传可能需要 1-2 分钟，请耐心等待')
  const data=new FormData();data.append('file',file.raw);uploading.value=true
  try{
    const result=await api<any>({method:'POST',url:`/knowledge-spaces/${selected.value.publicId}/documents`,data,headers:{'Content-Type':'multipart/form-data'},timeout:600000})
    if(result.status==='INDEXED')ElMessage.success('文件已完成解析和索引')
    else if(String(result.status).includes('FAILED'))ElMessage.error('文件已保存，但解析失败；请打开详情查看原因')
    else {ElMessage.info(`文件已上传，正在后台处理：${statusName(result.status)}`);startDocumentPolling(result.publicId)}
    await reloadCurrent()
  }finally{uploading.value=false}
}
async function remove(doc:any){
  await ElMessageBox.confirm('删除会同步移除原文件、切块与向量索引，且不可恢复。','永久删除知识文档',{type:'warning'})
  const token=await api<any>({method:'POST',url:`/documents/${doc.publicId}/deletion-requests`})
  await api({method:'POST',url:`/documents/${doc.publicId}/deletion`,data:{token:token.token}})
  ElMessage.info('删除请求已提交，后台正在清理原文件与索引');await reloadCurrent()
}
function startEdit(space:any){editingSpace.value=space;editName.value=space.name;editing.value=true}
async function renameSpace(){
  if(!editingSpace.value||!editName.value.trim())return
  await api({method:'PATCH',url:`/knowledge-spaces/${editingSpace.value.publicId}`,data:{name:editName.value.trim(),directionId:null,version:editingSpace.value.version}})
  editing.value=false;await load()
}
async function deleteSpace(space:any){
  await ElMessageBox.confirm(`删除知识空间「${space.name}」将同时移除其中所有文档、切块与向量索引，且不可恢复。`,'永久删除知识空间',{type:'warning'})
  await api({method:'DELETE',url:`/knowledge-spaces/${space.publicId}`})
  ElMessage.info('删除请求已提交，后台正在清理知识空间和文档')
  if(selected.value?.publicId===space.publicId){selected.value=null;documents.value=[]}
  await load()
}
function statusType(status:string){return status==='INDEXED'?'success':status?.includes('FAILED')?'danger':'warning'}
const statusNames:Record<string,string>={
  UPLOADED:'已上传',SECURITY_CHECKING:'安全检查',PARSING:'提取文字',OCR_PROCESSING:'OCR 识别中',
  CHUNKING:'切分文本',INDEXED:'索引完成',DELETING:'删除处理中',PURGED:'已彻底删除',DELETE_FAILED:'删除失败',PARSE_FAILED:'解析失败',INDEX_FAILED:'索引失败',
}
function statusName(status:string){return statusNames[status]||status||'未知状态'}
async function openDetail(doc:any){
  documentDetail.value=await api<any>({url:`/documents/${doc.publicId}`})
  detailVisible.value=true
}
async function createCategory(){
  if(!selected.value||!categoryName.value.trim())return
  await api({method:'POST',url:`/knowledge-spaces/${selected.value.publicId}/categories`,data:{parentId:categoryParent.value||null,name:categoryName.value.trim(),sortNo:categories.value.length}})
  categoryName.value=''
  categoryParent.value=''
  await choose(selected.value)
  ElMessage.success('资料分类已创建')
}
async function deleteCategory(category:any){
  await ElMessageBox.confirm(`确定删除分类“${category.name}”吗？分类中有文档或子分类时不能删除。`,'删除资料分类',{type:'warning'})
  await api({method:'DELETE',url:`/knowledge-spaces/${selected.value.publicId}/categories/${category.id}`})
  await choose(selected.value)
}
async function assignCategory(doc:any,categoryId:string){
  await api({method:'PATCH',url:`/documents/${doc.publicId}/category`,data:{categoryId:categoryId||null,version:doc.version}})
  await reloadCurrent()
  ElMessage.success('文档分类已更新')
}
</script>

<template>
  <div>
    <div class="page-head"><div><h2>把资料变成可检索的知识</h2><p>文件经过安全校验、解析、切块和索引；问答引用会精确回到来源片段。「图书」Tab 接入你的微信读书书架。</p></div><el-tabs v-model="activeTab" class="kb-tabs"><el-tab-pane label="资料" name="materials" /><el-tab-pane label="图书" name="books" /></el-tabs></div>
    <template v-if="activeTab === 'materials'">
      <div class="kb-switch kb-toolbar"><el-radio-group v-model="allMode" size="small"><el-radio-button :value="false">知识空间</el-radio-button><el-radio-button :value="true">全部资料</el-radio-button></el-radio-group><el-button v-if="!allMode" type="primary" @click="creating=true">新建知识空间</el-button></div>
      <div class="grid kb-grid" :class="{single:allMode}">
      <aside v-if="!allMode" class="panel spaces"><h3>知识空间</h3><div v-for="space in spaces" :key="space.publicId" class="space-item" :class="{active:selected?.publicId===space.publicId}" @click="choose(space)"><span class="space-icon">▤</span><div class="space-info"><b>{{space.name}}</b><small>{{space.status||'ACTIVE'}}</small></div><div class="space-actions" @click.stop><el-button link size="small" @click="startEdit(space)" title="重命名">✎</el-button><el-button link size="small" type="danger" @click="deleteSpace(space)" title="删除">✕</el-button></div></div><div v-if="!spaces.length" class="empty">还没有知识空间</div></aside>
      <section class="panel">
        <div class="panel-title"><div><h3>{{allMode?'全部资料':(selected?.name||'文档')}}</h3><p>{{allMode?'汇总所有知识空间中的文档，可按空间名称、文档名与分类检索。':'支持 PDF、DOCX、Markdown、TXT；扫描 PDF 会自动 OCR（最多 50 MB、100 页）。'}}</p></div><div class="document-actions"><template v-if="allMode"><el-input v-model="keyword" clearable placeholder="搜索文档名 / 空间名" style="width:190px"/><el-select v-model="allCategoryFilter" clearable placeholder="按分类筛选" style="width:170px"><el-option label="未分类" value="UNCATEGORIZED"/><el-option v-for="c in allCategories" :key="c.spacePublicId+'__'+c.id" :label="c.spaceName+' / '+displayPath(c)" :value="c.spacePublicId+'__'+c.id"/></el-select></template><template v-else><el-select v-if="selected" v-model="categoryFilter" clearable placeholder="按分类筛选" style="width:150px"><el-option label="未分类" value="UNCATEGORIZED"/><el-option v-for="category in categories" :key="category.id" :label="displayPath(category)" :value="category.id"/></el-select><el-button v-if="selected" @click="categoryDialog=true">管理分类</el-button><el-upload v-if="selected" :show-file-list="false" :auto-upload="false" :on-change="upload" accept=".pdf,.docx,.md,.txt"><el-button :loading="uploading">上传文档</el-button></el-upload></template></div></div>
        <el-table :key="allMode?'all':'space'" :data="allMode?filteredAll:filteredDocuments">
          <el-table-column v-if="allMode" label="知识空间" width="150"><template #default="{row}"><b>{{row.spaceName}}</b></template></el-table-column>
          <el-table-column label="文档" min-width="250"><template #default="{row}"><b>{{row.displayName}}</b><div class="muted file-meta">版本 {{row.activeVersionNo}} · {{row.visibility}}</div></template></el-table-column>
          <el-table-column label="处理状态" width="140"><template #default="{row}"><el-tag :type="statusType(row.status)" effect="plain">{{statusName(row.status)}}</el-tag></template></el-table-column>
          <el-table-column label="资料分类" width="180"><template #default="{row}"><el-select v-if="categoriesForDoc(row).length" :model-value="row.categoryPublicId||''" clearable placeholder="未分类" @change="(value:string)=>assignCategory(row,value)"><el-option v-for="category in categoriesForDoc(row)" :key="category.id" :label="displayPath(category)" :value="category.id"/></el-select><el-button v-else-if="!allMode" link type="primary" @click="categoryDialog=true">暂无分类，点击创建 →</el-button><span v-else class="no-cat">该空间暂无分类</span></template></el-table-column>
          <el-table-column prop="createdAt" label="上传时间" width="190"/>
          <el-table-column label="操作" width="190"><template #default="{row}"><el-button link type="primary" @click="openContent(row)">内容</el-button><el-button link @click="openDetail(row)">详情</el-button><el-button link type="danger" @click="remove(row)">删除</el-button></template></el-table-column>
        </el-table>
        <div v-if="allMode&&!allDocuments.length" class="upload-empty">还没有上传任何资料，先选一个知识空间上传</div>
        <div v-else-if="allMode&&!filteredAll.length" class="upload-empty">没有匹配的资料，换个关键词或分类试试</div>
        <div v-else-if="!documents.length" class="upload-empty">拖入学习资料，建立你的第一批可引用知识</div>
        <div v-else-if="!filteredDocuments.length" class="upload-empty">该分类下还没有文档，试试其他分类或清除筛选</div>
      </section>
      </div>
    </template>
    <BooksView v-else />
    <el-dialog v-model="creating" title="新建知识空间" width="420"><el-input v-model="newName" placeholder="例如：Java 后端课程" @keyup.enter="create"/><template #footer><el-button @click="creating=false">取消</el-button><el-button type="primary" @click="create">创建</el-button></template></el-dialog>
    <el-dialog v-model="editing" title="重命名知识空间" width="420"><el-input v-model="editName" placeholder="知识空间名称" @keyup.enter="renameSpace"/><template #footer><el-button @click="editing=false">取消</el-button><el-button type="primary" @click="renameSpace">保存</el-button></template></el-dialog>
    <el-dialog v-model="categoryDialog" title="管理资料分类" width="520">
      <div class="category-create"><el-select v-model="categoryParent" clearable placeholder="上级分类（可选）"><el-option v-for="category in categories" :key="category.id" :label="displayPath(category)" :value="category.id"/></el-select><el-input v-model="categoryName" maxlength="120" placeholder="例如：教材、讲义、练习题" @keyup.enter="createCategory"/><el-button type="primary" @click="createCategory">新增</el-button></div>
      <div v-if="categories.length" class="category-list"><div v-for="category in categories" :key="category.id"><span>{{displayPath(category)}}</span><el-button link type="danger" @click="deleteCategory(category)">删除</el-button></div></div>
      <el-empty v-else description="还没有分类，输入名称后点「新增」即可创建"/>
    </el-dialog>
    <el-dialog v-model="detailVisible" title="文档处理详情" width="680">
      <template v-if="documentDetail">
        <p><b>{{documentDetail.document?.displayName}}</b> · {{statusName(documentDetail.document?.status)}}</p>
        <el-table :data="documentDetail.versions||[]" size="small">
          <el-table-column prop="versionNo" label="版本" width="70"/>
          <el-table-column label="解析方式" min-width="180"><template #default="{row}">{{row.parserVersion||'—'}}</template></el-table-column>
          <el-table-column label="状态" width="120"><template #default="{row}">{{statusName(row.status)}}</template></el-table-column>
        </el-table>
        <h4>处理任务</h4>
        <el-table :data="documentDetail.jobs||[]" size="small">
          <el-table-column prop="jobType" label="任务" width="120"/>
          <el-table-column prop="status" label="状态" width="100"/>
          <el-table-column label="结果"><template #default="{row}"><span :class="{danger:row.status==='FAILED'}">{{row.errorMessage||'处理完成'}}</span></template></el-table-column>
        </el-table>
      </template>
    </el-dialog>
    <el-drawer v-model="contentVisible" size="58%" :title="contentDoc?.displayName||'文档内容'">
      <div v-loading="contentLoading" class="content-viewer">
        <p v-if="contentDoc" class="muted">版本 V{{contentDoc.activeVersionNo}} · {{statusName(contentDoc.status)}} · 共 {{contentChunks.length}} 段</p>
        <template v-if="contentChunks.length">
          <article v-for="(chunk,index) in contentChunks" :key="index" class="chunk-card">
            <div class="chunk-meta">
              <b>第 {{chunk.chunkNo}} 段</b>
              <span v-if="chunkTitle(chunk)">{{chunkTitle(chunk)}}</span>
              <span v-if="chunk.pageFrom!=null">第 {{chunk.pageFrom}}{{chunk.pageTo!=null&&chunk.pageTo!==chunk.pageFrom?'-'+chunk.pageTo:''}} 页</span>
              <span v-if="chunk.paragraphFrom!=null">第 {{chunk.paragraphFrom}} 段</span>
              <span>{{chunk.tokenCount??0}} tokens</span>
            </div>
            <pre class="chunk-text">{{chunk.text}}</pre>
          </article>
        </template>
        <el-empty v-else-if="!contentLoading" description="该文档没有可预览的内容（可能解析失败或尚未索引）" :image-size="70"/>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>.kb-tabs{max-width:320px}.kb-toolbar{margin-bottom:14px}.content-viewer{display:grid;gap:14px}.chunk-card{border:1px solid #e2e7e1;border-radius:10px;overflow:hidden}.chunk-meta{display:flex;gap:12px;align-items:center;flex-wrap:wrap;padding:8px 12px;background:#f3f6f3;font-size:11px;color:var(--muted)}.chunk-meta b{color:#2d8a63}.chunk-text{margin:0;padding:12px 14px;white-space:pre-wrap;word-break:break-word;font-family:Consolas,'Courier New',monospace;font-size:12.5px;line-height:1.9;color:var(--text)}.kb-switch{display:flex;align-items:center;gap:10px;flex-wrap:wrap}.kb-grid{grid-template-columns:260px 1fr}.kb-grid.single{grid-template-columns:1fr}.no-cat{display:inline-flex;align-items:center;height:32px;color:var(--muted);font-size:12px}.spaces{padding:13px}.spaces h3{padding:4px 10px}.space-item{cursor:pointer;border:0;background:transparent;width:100%;display:flex;gap:10px;align-items:center;text-align:left;padding:12px;border-radius:9px;transition:background .15s}.space-item.active{background:#e7efe9;color:var(--green)}.space-icon{flex:none}.space-info{flex:1;min-width:0}.space-info b,.space-info small{display:block}.space-info small{font-size:10px;color:var(--muted);margin-top:3px}.space-actions,.document-actions,.category-create{display:flex;gap:8px;align-items:center}.space-actions{gap:2px;opacity:0;transition:opacity .15s}.space-item:hover .space-actions{opacity:1}.file-meta{font-size:10px;margin-top:5px}.category-list{display:grid;gap:8px;margin-top:16px}.category-list>div{display:flex;justify-content:space-between;align-items:center;padding:9px 12px;border-radius:9px;background:#f3f6f3}.upload-empty{border:1px dashed #cbd1ca;border-radius:12px;text-align:center;color:var(--muted);padding:50px;margin-top:20px}.danger{color:var(--el-color-danger)}@media(max-width:800px){.kb-grid{grid-template-columns:1fr}}</style>
