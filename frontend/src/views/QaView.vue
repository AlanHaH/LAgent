<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import MarkdownIt from 'markdown-it'
import DOMPurify from 'dompurify'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Loading } from '@element-plus/icons-vue'
import { api, postSse } from '../api/http'
// 正文中的 [S1] 引用标注渲染成高亮徽章，视觉上可点击定位到下方引用卡片
const md=new MarkdownIt({html:false,linkify:true,breaks:true})
const defaultText=md.renderer.rules.text
md.renderer.rules.text=(tokens,idx,options,env,self)=>{
  const rendered=defaultText?defaultText(tokens,idx,options,env,self):String(tokens[idx].content)
  return rendered.replace(/\[(S[1-9]\d*)\]/g,'<b class="cite-badge">[$1]</b>')
}
const spaces=ref<any[]>([]), docsBySpace=ref<Record<string,any[]>>({}), expanded=ref<string[]>([]), selectedDocs=ref<string[]>([]), sessions=ref<any[]>([]), current=ref<any>(), messages=ref<any[]>([]), input=ref(''), asking=ref(false)
const activeCitation=ref<any>(null)
function toggleCitation(c:any){activeCitation.value=activeCitation.value?.citationCode===c.citationCode?null:c}
const canAsk=computed(()=>Boolean(input.value.trim()&&selectedDocs.value.length))
const scopeLocked=computed(()=>Boolean(current.value))
function docsOf(space:any){return docsBySpace.value[space.publicId]||[]}
// 只有已索引文件可参与检索；未索引文件置灰不可勾选
function selectableIds(space:any){return docsOf(space).filter((d:any)=>d.status==='INDEXED').map((d:any)=>d.publicId)}
function spaceState(space:any):'checked'|'indeterminate'|'unchecked'{
  const ids=selectableIds(space)
  if(!ids.length)return'unchecked'
  const on=ids.filter((id:string)=>selectedDocs.value.includes(id)).length
  if(on===ids.length)return'checked'
  return on?'indeterminate':'unchecked'
}
function explainLockedScope(){ElMessage.info('当前对话的检索范围已经固定；点击“新对话”后即可重新选择资料')}
function toggleSpace(space:any){if(scopeLocked.value)return explainLockedScope();const ids=selectableIds(space);if(ids.every((id:string)=>selectedDocs.value.includes(id)))selectedDocs.value=selectedDocs.value.filter((id:string)=>!ids.includes(id));else selectedDocs.value=[...new Set([...selectedDocs.value,...ids])]}
function toggleDoc(d:any){if(scopeLocked.value)return explainLockedScope();selectedDocs.value=selectedDocs.value.includes(d.publicId)?selectedDocs.value.filter((id:string)=>id!==d.publicId):[...selectedDocs.value,d.publicId]}
function toggleExpand(space:any){expanded.value=expanded.value.includes(space.publicId)?expanded.value.filter((id:string)=>id!==space.publicId):[...expanded.value,space.publicId]}
// 会话范围：新版 {"scope":"DOCUMENT","ids":[...]}；旧版是空间 publicId 数组
function scopeOf(s:any){try{const raw=s?.selectedSpaceJson;if(!raw)return{type:'SPACE',ids:[]};const parsed=JSON.parse(raw);if(parsed&&typeof parsed==='object'&&!Array.isArray(parsed))return{type:'DOCUMENT',ids:parsed.ids||[]};return{type:'SPACE',ids:Array.isArray(parsed)?parsed:[]}}catch{return{type:'SPACE',ids:[]}}}
function applyScope(scope:any){
  const allDocs=Object.values(docsBySpace.value).flat() as any[]
  if(scope.type==='DOCUMENT'){
    selectedDocs.value=scope.ids.filter((id:string)=>allDocs.some((d:any)=>d.publicId===id))
    expanded.value=spaces.value.filter((s:any)=>docsOf(s).some((d:any)=>scope.ids.includes(d.publicId))).map((s:any)=>s.publicId)
  }else{
    // 旧会话按整个空间检索 → 展示为该空间全部可检索文件
    selectedDocs.value=spaces.value.filter((s:any)=>scope.ids.includes(s.publicId)).flatMap(selectableIds)
    expanded.value=spaces.value.filter((s:any)=>scope.ids.includes(s.publicId)).map((s:any)=>s.publicId)
  }
}
onMounted(async()=>{
  spaces.value=await api<any[]>({url:'/knowledge-spaces'})
  docsBySpace.value=Object.fromEntries(await Promise.all(spaces.value.map(async(s:any)=>[s.publicId,await api<any[]>({url:`/knowledge-spaces/${s.publicId}/documents`})] as const)))
  sessions.value=await api<any[]>({url:'/qa-sessions'})
  selectedDocs.value=spaces.value.flatMap(selectableIds)
  if(sessions.value[0])await open(sessions.value[0])
})
function messageView(value:any){return value?.message?{...value.message,citations:value.citations||[]}:value}
async function open(s:any){current.value=s;messages.value=(await api<any[]>({url:`/qa-sessions/${s.publicId}/messages`})).map(messageView);applyScope(scopeOf(s))}
async function ensure(){if(current.value)return;current.value=await api<any>({method:'POST',url:'/qa-sessions',data:{title:'学习问答',documentIds:selectedDocs.value}});sessions.value.unshift(current.value)}
async function ask(){
  if(!canAsk.value)return
  await ensure()
  const content=input.value
  input.value=''
  messages.value.push({role:'USER',content,citations:[]})
  const assistant:any={role:'ASSISTANT',content:'',citations:[],generating:true}
  messages.value.push(assistant)
  asking.value=true
  try{
    await postSse(`/qa-sessions/${current.value.publicId}/messages`,{content},({event,data})=>{
      if(event==='message.delta')assistant.content+=data?.delta||''
      else if(event==='message.replaced')assistant.content=data?.content||''
      else if(event==='citation.ready')assistant.citations.push({...data,citationCode:data?.citationCode||data?.citationId})
      else if(event==='message.completed'){
        const final=messageView(data?.assistantMessage)
        Object.assign(assistant,final,{generating:false})
        if(data?.evidenceSufficient===false)ElMessage.warning('资料中没有足够证据，系统已拒绝臆测')
      }else if(event==='message.failed')throw new Error(data?.message||'回答生成失败')
    })
    if(assistant.generating)throw new Error('回答流意外中断，请重试')
  }catch(error:any){
    if(!assistant.content)assistant.content=`回答生成失败：${error?.message||'请稍后重试'}`
    assistant.generating=false
    ElMessage.error(error?.message||'回答生成失败')
  }finally{asking.value=false}
}
function html(s:string){return DOMPurify.sanitize(md.render(s||''))}
async function rate(m:any,rating:number){await api({method:'PUT',url:`/qa-messages/${m.publicId}/feedback`,data:{rating,reasonCode:rating>3?'HELPFUL':'NEEDS_IMPROVEMENT',comment:''}});ElMessage.success('反馈已记录')}
async function renameSession(s:any){
  let value=''
  try{const result=await ElMessageBox.prompt('请输入新标题','重命名对话',{inputValue:s.title||'',inputPattern:/\S/,inputErrorMessage:'标题不能为空',inputValidator:(v:string)=>v.trim().length>200?'标题不能超过 200 字':true});value=(result.value||'').trim()}catch{return}
  if(!value||value===s.title)return
  const updated=await api<any>({method:'PATCH',url:`/qa-sessions/${s.publicId}`,data:{title:value}})
  s.title=updated.title
  if(current.value?.publicId===s.publicId)current.value.title=updated.title
  ElMessage.success('对话已重命名')
}
async function deleteSession(s:any){
  await ElMessageBox.confirm(`删除对话「${s.title||'未命名对话'}」及其所有消息？`,'删除对话',{type:'warning'})
  await api({method:'DELETE',url:`/qa-sessions/${s.publicId}`})
  sessions.value=sessions.value.filter((x:any)=>x.publicId!==s.publicId)
  if(current.value?.publicId===s.publicId){current.value=undefined;messages.value=[]}
  ElMessage.success('对话已删除')
}
</script>
<template><div class="qa-shell"><aside class="panel qa-side"><el-button type="primary" class="full" @click="current=undefined;messages=[]">＋ 新对话</el-button><h4>历史对话</h4><button v-for="s in sessions" :key="s.publicId" class="session-item" :class="{active:s.publicId===current?.publicId}" @click="open(s)"><span class="session-title">{{s.title||'未命名对话'}}</span><span class="session-del" @click.stop="deleteSession(s)">✕</span><span class="session-rename" @click.stop="renameSession(s)">✎</span></button><h4>检索范围</h4><div class="scope-tree"><div v-for="s in spaces" :key="s.publicId" class="scope-space"><div class="space-row"><button class="arrow" :class="{open:expanded.includes(s.publicId)}" @click="toggleExpand(s)">▸</button><el-checkbox :indeterminate="spaceState(s)==='indeterminate'" :model-value="spaceState(s)==='checked'" @change="toggleSpace(s)">{{s.name}}</el-checkbox></div><div v-if="expanded.includes(s.publicId)" class="doc-list"><div v-for="d in docsOf(s)" :key="d.publicId" class="doc-row"><el-checkbox :model-value="selectedDocs.includes(d.publicId)" :disabled="d.status!=='INDEXED'" @change="toggleDoc(d)">{{d.displayName}}</el-checkbox><span v-if="d.status!=='INDEXED'" class="doc-status">未索引</span></div><div v-if="!docsOf(s).length" class="doc-empty">暂无文档</div></div></div></div><div class="scope-count">已选 {{selectedDocs.length}} 个文件</div></aside><section class="panel chat"><div class="chat-head"><div><h3>基于证据的学习问答</h3><p>每个结论都应带引用；没有证据时明确拒答。</p></div><span class="tag">RAG</span></div><div class="messages"><div v-if="!messages.length" class="qa-welcome"><span>问</span><h3>从你的资料开始提问</h3><p>试试：“用三个要点解释这个概念，并指出出处。”</p></div><div v-for="(m,i) in messages" :key="m.publicId||i" :class="['message',String(m.role).toLowerCase()]"><div class="avatar">{{m.role==='USER'?'我':'序'}}</div><div class="bubble"><div v-if="m.generating"><div v-if="!m.content" class="waiting"><el-icon class="is-loading wait-circle"><Loading /></el-icon><span class="typing">正在检索并核对引用…</span></div><template v-else><div class="markdown" v-html="html(m.content)"/><span class="stream-cursor"/></template></div><div v-else class="markdown" v-html="html(m.content)"/><div v-if="m.citations?.length" class="citations"><b>引用依据</b><div v-for="c in m.citations" :key="c.citationCode"><button class="citation-item" :class="{active:activeCitation?.citationCode===c.citationCode}" @click="toggleCitation(c)"><span class="cite-no">[{{c.citationCode}}]</span><span class="cite-file">{{c.fileName||('资料片段 #'+c.chunkId)}}</span><span v-if="c.pageFrom!=null" class="cite-page">第 {{c.pageFrom}}{{c.pageTo!=null&&c.pageTo!==c.pageFrom?'-'+c.pageTo:''}} 页</span></button><div v-if="activeCitation?.citationCode===c.citationCode" class="citation-preview"><span class="quote-mark">原文</span><p>{{c.quotePreview||'（无片段预览）'}}</p></div></div></div><div v-if="m.role!=='USER'&&!m.generating&&m.publicId" class="feedback"><span>这条回答有帮助吗？</span><button @click="rate(m,5)">有帮助</button><button @click="rate(m,2)">需改进</button></div></div></div></div><div class="composer"><el-input v-model="input" type="textarea" autosize placeholder="输入问题，Ctrl + Enter 发送" @keydown.ctrl.enter.prevent="ask"/><el-button type="primary" :loading="asking" :disabled="!canAsk" @click="ask">发送</el-button></div></section></div></template>
<style scoped>.qa-shell{display:grid;grid-template-columns:255px 1fr;gap:18px;height:calc(100vh - 150px)}.qa-side{padding:14px;overflow:auto}.qa-side h4{font-size:11px;color:var(--muted);letter-spacing:.12em;margin:24px 8px 8px}.qa-side>button:not(.el-button){width:100%;border:0;background:transparent;text-align:left;padding:10px;border-radius:8px;color:#56625a}.qa-side>button.active{background:#e7efe9;color:var(--green)}.session-item{display:flex;align-items:center;justify-content:space-between;gap:8px;width:100%;border:0;background:transparent;text-align:left;padding:10px;border-radius:8px;color:#56625a;cursor:pointer}.session-item.active{background:#e7efe9;color:var(--green)}.session-title{flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.session-del{flex:none;opacity:0;color:#c33;font-size:11px;padding:2px 4px;transition:opacity .15s}.session-item:hover .session-del,.session-item:hover .session-rename{opacity:1}.session-rename{flex:none;opacity:0;color:var(--green);font-size:11px;padding:2px 4px;transition:opacity .15s;cursor:pointer}.scope-tree{display:flex;flex-direction:column;gap:2px;padding:0 6px}.scope-space{display:flex;flex-direction:column;gap:1px}.space-row{display:flex;align-items:center;gap:2px}.space-row .arrow{border:0;background:transparent;color:var(--muted);font-size:10px;width:18px;height:24px;flex:none;cursor:pointer;transition:transform .15s;padding:0}.space-row .arrow.open{transform:rotate(90deg)}.doc-list{margin-left:20px;display:flex;flex-direction:column;gap:1px}.doc-row{display:flex;align-items:center;gap:4px;min-width:0}.doc-row :deep(.el-checkbox){margin-right:0}.doc-row :deep(.el-checkbox__label){font-size:12px;color:#56625a;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.doc-status{font-size:10px;color:var(--muted);flex:none}.doc-empty{font-size:11px;color:var(--muted);padding:2px 4px 6px}.scope-count{font-size:10px;color:var(--muted);padding:6px 8px 0}.chat{padding:0;display:flex;flex-direction:column;min-height:0}.chat-head{padding:18px 22px;border-bottom:1px solid var(--line);display:flex;justify-content:space-between}.chat-head h3,.chat-head p{margin:0}.chat-head p{font-size:11px;color:var(--muted);margin-top:4px}.messages{flex:1;overflow:auto;padding:22px}.qa-welcome{text-align:center;padding:12vh 0;color:var(--muted)}.qa-welcome span{display:grid;place-items:center;margin:auto;width:55px;height:55px;border-radius:18px;background:var(--green);color:#fff;font:24px 'DM Serif Display'}.qa-welcome h3{color:var(--ink)}.message{display:flex;gap:11px;margin-bottom:20px}.message.user{flex-direction:row-reverse}.avatar{display:grid;place-items:center;width:34px;height:34px;border-radius:10px;background:#dceae0;color:var(--green);font-weight:700;flex:none}.message.user .avatar{background:#e9e2d6;color:#6c583b}.bubble{max-width:80%;background:#eef2ed;border-radius:4px 14px 14px 14px;padding:12px 15px;line-height:1.7;font-size:13px}.message.user .bubble{background:#173d31;color:#fff;border-radius:14px 4px 14px 14px}.waiting{display:flex;align-items:center;gap:8px;color:#56625a}.wait-circle{font-size:16px;color:var(--green)}.stream-cursor{display:inline-block;width:2px;height:1em;background:var(--green);margin-left:2px;vertical-align:text-bottom;animation:cursor-blink 1s steps(1) infinite}@keyframes cursor-blink{50%{opacity:0}}.citations{border-top:1px solid #d6ddd7;margin-top:12px;padding-top:10px}.citations b{font-size:10px;letter-spacing:.1em;display:block;margin-bottom:6px}.citation-item{border:0;background:transparent;color:var(--green);font-size:11px;padding:6px 8px;text-align:left;display:flex;gap:8px;align-items:center;width:100%;border-radius:8px;cursor:pointer}.citation-item:hover,.citation-item.active{background:#e2ede4}.cite-no{font-weight:700;flex:none}.cite-file{flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:#3c6b52}.cite-page{flex:none;font-size:10px;color:var(--muted)}.citation-preview{background:#f7faf5;border:1px solid #e2e7e1;border-radius:8px;padding:10px 12px;margin:4px 0 8px}.quote-mark{font-size:9px;letter-spacing:.1em;color:var(--muted)}.citation-preview p{margin:6px 0 0;font-size:11.5px;line-height:1.7;color:#42524a}.cite-badge{color:#2d8a63;background:#dceae0;border-radius:5px;padding:0 4px;font-size:11px;margin:0 1px;white-space:nowrap}.feedback{font-size:10px;color:var(--muted);margin-top:8px}.feedback button{border:0;background:transparent;color:var(--green)}.composer{display:grid;grid-template-columns:1fr auto;gap:10px;padding:16px;border-top:1px solid var(--line)}@media(max-width:800px){.qa-shell{grid-template-columns:1fr;height:auto}.qa-side{display:none}.chat{min-height:70vh}}</style>
