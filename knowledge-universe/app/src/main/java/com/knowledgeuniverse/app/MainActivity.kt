package com.knowledgeuniverse.app
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

private val bg=Color(0xFF10172A); private val panel=Color(0xFF1A2540); private val violet=Color(0xFF9B8CFF); private val mint=Color(0xFF65E0C0); private val muted=Color(0xFFABB6D0)
data class Article(val id:Int,val subject:String,val title:String,val summary:String,val body:String)
data class Subject(val name:String,val emoji:String,val tint:Color,val desc:String)
private val subjects=listOf(Subject("天文","🌌",Color(0xFF9B8CFF),"探索宇宙与星辰"),Subject("地理","🌍",Color(0xFF65E0C0),"认识地球与世界"),Subject("历史","🏛️",Color(0xFFFFBE72),"理解文明与时间"),Subject("文学","📚",Color(0xFFFF8FA3),"阅读经典与思想"),Subject("信息安全","🛡️",Color(0xFF70B7FF),"学习安全原理与防护"),Subject("英语","🔤",Color(0xFFB6A1FF),"词汇、语法与表达"))
private val articles=listOf(
Article(1,"天文","恒星如何发光","恒星通过核心核聚变释放能量。","恒星主要由氢和氦构成。在许多恒星核心，氢核聚变形成氦并释放能量。恒星颜色与表面温度有关。"),
Article(2,"天文","认识太阳系","太阳的引力维系行星和小天体的轨道。","太阳系包含太阳、八大行星、矮行星、卫星、小行星和彗星等。内侧是岩石行星，外侧有气态巨行星和冰巨行星。"),
Article(3,"地理","为什么会有四季","地轴倾斜是四季变化的关键原因。","地球自转轴相对公转轨道平面倾斜约 23.4°。不同半球接收太阳光的角度和昼长发生变化，因此产生季节差异。"),
Article(4,"地理","板块构造","地球岩石圈由不断运动的板块组成。","板块边界可发生张裂、汇聚或平移，相关过程塑造山脉、海沟、火山和地震带。"),
Article(5,"历史","文艺复兴","欧洲思想艺术运动重新关注古典文化。","文艺复兴约从十四世纪的意大利兴起，在古典学术、艺术创新、科学观察和人文主义之间形成复杂互动。"),
Article(6,"历史","丝绸之路","古代欧亚交通网络促进商品与知识交流。","丝绸之路由陆路和海路组成多条交流网络。除商品外，宗教、技术、艺术和语言也沿这些网络传播。"),
Article(7,"文学","如何细读一篇小说","从叙事视角、人物、意象和结构建立解释。","观察叙述者、人物选择、冲突、重复意象和语言节奏。解释应由文本证据支持，并考虑作品的历史语境。"),
Article(8,"文学","什么是隐喻","隐喻借一种事物理解另一种事物。","隐喻把一个领域的特征映射到另一个领域。例如“时间是河流”突出流动与不可逆。"),
Article(9,"信息安全","最小权限原则","只授予完成任务所必需的权限。","用户、服务和程序仅应拥有执行工作所需的最少权限，并结合短期凭证、角色分离、权限审查与审计日志。"),
Article(10,"信息安全","识别钓鱼邮件","核对发件域名、链接目标与异常请求。","警惕索要密码、验证码或密钥的内容。通过可信渠道独立确认并报告可疑邮件，不要打开未知附件。"),
Article(11,"英语","Build a useful vocabulary","Learn words in context, not as isolated translations.","Learn a few words, read example sentences, say them aloud, and review them at increasing intervals. Active recall helps you remember."),
Article(12,"英语","Present perfect","Connect a past event with the present.","Form: have/has + past participle. Example: I have finished the report. It often describes experience, a recent result, or a situation continuing to the present.")
)
class Store(c:android.content.Context):SQLiteOpenHelper(c,"knowledge.db",null,1){
 override fun onCreate(d:SQLiteDatabase){d.execSQL("CREATE TABLE users(id INTEGER PRIMARY KEY,name TEXT UNIQUE COLLATE NOCASE,salt TEXT,hash TEXT)");d.execSQL("CREATE TABLE progress(uid INTEGER,aid INTEGER,seconds INTEGER DEFAULT 0,done INTEGER DEFAULT 0,at INTEGER,PRIMARY KEY(uid,aid))");d.execSQL("CREATE TABLE marks(uid INTEGER,aid INTEGER,PRIMARY KEY(uid,aid))");d.execSQL("CREATE TABLE notes(uid INTEGER,aid INTEGER,note TEXT,PRIMARY KEY(uid,aid))");d.execSQL("CREATE TABLE quiz(uid INTEGER,aid INTEGER,correct INTEGER,at INTEGER)")}
 override fun onUpgrade(d:SQLiteDatabase,o:Int,n:Int){}
 private fun hash(p:CharArray,s:ByteArray):ByteArray{val k=PBEKeySpec(p,s,210000,256);return try{SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(k).encoded}finally{k.clearPassword()}}
 fun register(n:String,p:String):String{if(!n.trim().matches(Regex("[A-Za-z0-9_\\u4e00-\\u9fa5]{3,24}")))return "用户名需为 3-24 位字母、数字、下划线或中文";if(p.length<8)return "密码至少 8 位";val s=ByteArray(16).also{SecureRandom().nextBytes(it)};return try{writableDatabase.insertOrThrow("users",null,ContentValues().apply{put("name",n.trim());put("salt",Base64.encodeToString(s,Base64.NO_WRAP));put("hash",Base64.encodeToString(hash(p.toCharArray(),s),Base64.NO_WRAP))});"注册成功，请登录"}catch(_:Exception){"用户名已存在或创建失败"}}
 fun login(n:String,p:String):Long=readableDatabase.rawQuery("SELECT id,salt,hash FROM users WHERE name=?",arrayOf(n.trim())).use{if(!it.moveToFirst())0L else if(MessageDigest.isEqual(hash(p.toCharArray(),Base64.decode(it.getString(1),Base64.NO_WRAP)),Base64.decode(it.getString(2),Base64.NO_WRAP)))it.getLong(0) else 0L}
 fun name(u:Long)=readableDatabase.rawQuery("SELECT name FROM users WHERE id=?",arrayOf(u.toString())).use{if(it.moveToFirst())it.getString(0) else ""}
 fun scalar(q:String,u:Long)=readableDatabase.rawQuery(q,arrayOf(u.toString())).use{if(it.moveToFirst())it.getInt(0) else 0}
 fun done(u:Long)=scalar("SELECT COUNT(*) FROM progress WHERE uid=? AND done=1",u)
 fun minutes(u:Long)=scalar("SELECT COALESCE(SUM(seconds),0)/60 FROM progress WHERE uid=?",u)
 fun quizzes(u:Long)=scalar("SELECT COUNT(*) FROM quiz WHERE uid=?",u)
 fun isDone(u:Long,a:Int)=readableDatabase.rawQuery("SELECT done FROM progress WHERE uid=? AND aid=?",arrayOf(u.toString(),a.toString())).use{it.moveToFirst()&&it.getInt(0)==1}
 fun record(u:Long,a:Int,finished:Boolean){writableDatabase.execSQL("INSERT INTO progress(uid,aid,seconds,done,at) VALUES(?,?,60,?,?) ON CONFLICT(uid,aid) DO UPDATE SET seconds=seconds+60,done=MAX(done,excluded.done),at=excluded.at",arrayOf(u,a,if(finished)1 else 0,System.currentTimeMillis()))}
 fun marked(u:Long,a:Int)=readableDatabase.rawQuery("SELECT 1 FROM marks WHERE uid=? AND aid=?",arrayOf(u.toString(),a.toString())).use{it.moveToFirst()}
 fun toggle(u:Long,a:Int){if(marked(u,a))writableDatabase.delete("marks","uid=? AND aid=?",arrayOf(u.toString(),a.toString()))else writableDatabase.execSQL("INSERT OR IGNORE INTO marks VALUES(?,?)",arrayOf(u,a))}
 fun note(u:Long,a:Int)=readableDatabase.rawQuery("SELECT note FROM notes WHERE uid=? AND aid=?",arrayOf(u.toString(),a.toString())).use{if(it.moveToFirst())it.getString(0) else ""}
 fun saveNote(u:Long,a:Int,n:String){writableDatabase.execSQL("INSERT OR REPLACE INTO notes VALUES(?,?,?)",arrayOf(u,a,n))}
 fun quiz(u:Long,a:Int,ok:Boolean){writableDatabase.execSQL("INSERT INTO quiz VALUES(?,?,?,?)",arrayOf(u,a,if(ok)1 else 0,System.currentTimeMillis()))}
}
class MainActivity:ComponentActivity(){override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);setContent{App(Store(this))}}}
@Composable private fun App(db:Store){
 var uid by remember{mutableLongStateOf(0L)};var page by remember{mutableStateOf("首页")};var opened by remember{mutableStateOf<Article?>(null)};var search by remember{mutableStateOf("")};var filter by remember{mutableStateOf("全部")};var username by remember{mutableStateOf("")};var password by remember{mutableStateOf("")};var registering by remember{mutableStateOf(false)};var message by remember{mutableStateOf("")};var revision by remember{mutableIntStateOf(0)}
 MaterialTheme(colorScheme=darkColorScheme(primary=violet,secondary=mint,background=bg,surface=panel)){
 Surface(Modifier.fillMaxSize(),color=bg){
 if(uid==0L) Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF252653),bg))).padding(24.dp),verticalArrangement=Arrangement.Center){
 Text("✦",fontSize=56.sp,color=violet);Text("知界",fontSize=38.sp,fontWeight=FontWeight.Bold);Text("KNOWLEDGE UNIVERSE",fontSize=11.sp,color=mint,letterSpacing=3.sp);Text("你的离线知识宇宙",fontSize=20.sp);Text("本地账户 · 数据只保存在此设备",fontSize=13.sp,color=muted);Spacer(Modifier.height(24.dp))
 OutlinedTextField(username,{username=it},label={Text("用户名")},singleLine=true,modifier=Modifier.fillMaxWidth());Spacer(Modifier.height(8.dp));OutlinedTextField(password,{password=it},label={Text("密码（至少 8 位）")},singleLine=true,visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),modifier=Modifier.fillMaxWidth());Spacer(Modifier.height(14.dp))
 Button(onClick={if(registering){message=db.register(username,password);if(message=="注册成功，请登录")registering=false}else{uid=db.login(username,password);message=if(uid==0L)"账号或密码错误" else ""}},modifier=Modifier.fillMaxWidth()){Text(if(registering)"创建本地账户" else "安全登录")}
 TextButton(onClick={registering=!registering;message=""},modifier=Modifier.align(Alignment.CenterHorizontally)){Text(if(registering)"已有账户？登录" else "没有账户？注册")};if(message.isNotBlank())Text(message,color=mint);Text("无网络权限 · 不上传数据 · 不云同步",fontSize=12.sp,color=muted)
 } else if(opened!=null) ArticlePage(db,uid,opened!!,{opened=null;revision++},{revision++}) else Column(Modifier.fillMaxSize()){
 Row(Modifier.fillMaxWidth().padding(18.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("知界 ✦",fontSize=25.sp,fontWeight=FontWeight.Bold);Text("KNOWLEDGE UNIVERSE",fontSize=10.sp,color=mint,letterSpacing=2.sp)};Text("你好，"+db.name(uid),fontSize=12.sp,color=muted)}
 when(page){
 "首页"->LazyColumn(contentPadding=PaddingValues(18.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
 item{Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Color(0xFF37356E),Color(0xFF1B3150))),RoundedCornerShape(24.dp)).padding(20.dp)){Text("今天，探索一点新知",fontSize=22.sp,fontWeight=FontWeight.Bold);Text("每天 15 分钟，让好奇心持续生长。",fontSize=13.sp,color=muted);Spacer(Modifier.height(14.dp));Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Metric("已学",db.done(uid).toString()+" 篇");Metric("时长",db.minutes(uid).toString()+" 分钟");Metric("答题",db.quizzes(uid).toString()+" 次")}}}
 item{Text("探索六大学科",fontSize=19.sp,fontWeight=FontWeight.Bold);Text("从兴趣开始，建立知识地图",fontSize=12.sp,color=muted)}
 items(subjects.chunked(2)){pair->Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){pair.forEach{s->Column(Modifier.weight(1f).background(panel,RoundedCornerShape(18.dp)).clickable{filter=s.name;page="知识库"}.padding(14.dp)){Text(s.emoji,fontSize=28.sp);Text(s.name,fontWeight=FontWeight.Bold,color=s.tint);Text(s.desc,fontSize=11.sp,color=muted)}};if(pair.size==1)Spacer(Modifier.weight(1f))}}
 item{Text("推荐阅读",fontSize=19.sp,fontWeight=FontWeight.Bold)};items(articles.take(4)){a->ArticleCard(a,db.isDone(uid,a.id)){opened=a}}
 }
 "知识库"->LazyColumn(contentPadding=PaddingValues(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
 item{Text("知识库",fontSize=27.sp,fontWeight=FontWeight.Bold);Text("全部内容均可离线阅读",fontSize=13.sp,color=muted)}
 item{OutlinedTextField(search,{search=it},modifier=Modifier.fillMaxWidth(),singleLine=true,placeholder={Text("搜索主题或关键词")},leadingIcon={Icon(Icons.Default.Search,null)})}
 item{Row(horizontalArrangement=Arrangement.spacedBy(5.dp)){(listOf("全部")+subjects.map{it.name}).forEach{f->FilterChip(selected=filter==f,onClick={filter=f},label={Text(f,fontSize=10.sp)})}}}
 items(articles.filter{(filter=="全部"||it.subject==filter)&&(search.isBlank()||it.title.contains(search,true)||it.body.contains(search,true)||it.summary.contains(search,true)||it.subject.contains(search,true))}){a->ArticleCard(a,db.isDone(uid,a.id)){opened=a}}
 }
 "学习"->LazyColumn(contentPadding=PaddingValues(18.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
 item{Text("学习中心",fontSize=27.sp,fontWeight=FontWeight.Bold);Text("每一次阅读都成为积累",fontSize=13.sp,color=muted)}
 item{Column(Modifier.fillMaxWidth().background(panel,RoundedCornerShape(18.dp)).padding(18.dp)){Text("知识完成进度",fontWeight=FontWeight.Bold);Spacer(Modifier.height(10.dp));LinearProgressIndicator(progress={db.done(uid).toFloat()/articles.size},modifier=Modifier.fillMaxWidth().height(8.dp),color=mint);Text(db.done(uid).toString()+" / "+articles.size+" 篇 · "+(db.done(uid)*100/articles.size)+"% 完成",fontSize=12.sp,color=muted);Text("累计学习 "+db.minutes(uid)+" 分钟 · 测验 "+db.quizzes(uid)+" 次",fontSize=12.sp,color=muted)}}
 item{Text("继续学习",fontSize=19.sp,fontWeight=FontWeight.Bold)};items(articles){a->ArticleCard(a,db.isDone(uid,a.id)){opened=a}}
 }
 else->LazyColumn(contentPadding=PaddingValues(18.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
 item{Text("我的",fontSize=27.sp,fontWeight=FontWeight.Bold);Text("个人学习档案与隐私",fontSize=13.sp,color=muted)}
 item{Column(Modifier.fillMaxWidth().background(panel,RoundedCornerShape(18.dp)).padding(18.dp)){Text("👤  "+db.name(uid),fontSize=20.sp,fontWeight=FontWeight.Bold);Text("本机离线账户",color=mint);Spacer(Modifier.height(12.dp));Text("🔒 随机盐 + PBKDF2 密码派生值",fontSize=12.sp,color=muted);Text("📵 应用没有 INTERNET 权限",fontSize=12.sp,color=muted);Text("💾 学习进度、收藏、笔记均保存在本机",fontSize=12.sp,color=muted);Text("⚠️ 卸载或清除数据会删除记录",fontSize=12.sp,color=muted)}}
 item{OutlinedButton(onClick={uid=0;username="";password="";page="首页"},modifier=Modifier.fillMaxWidth()){Icon(Icons.Default.Logout,null);Spacer(Modifier.width(8.dp));Text("退出本地账户")}}
 }
 }
 NavigationBar(containerColor=panel){listOf("首页","知识库","学习","我的").forEachIndexed{i,p->NavigationBarItem(selected=page==p,onClick={page=p},icon={Icon(listOf(Icons.Default.Home,Icons.Default.MenuBook,Icons.Default.School,Icons.Default.Person)[i],contentDescription=p)},label={Text(p)},colors=NavigationBarItemDefaults.colors(selectedIconColor=violet,selectedTextColor=violet,indicatorColor=Color(0xFF30365A),unselectedIconColor=muted,unselectedTextColor=muted))}}
 }
 }
 }
}
@Composable private fun Metric(label:String,value:String){Column(Modifier.width(88.dp).background(Color(0x33202A48),RoundedCornerShape(12.dp)).padding(10.dp)){Text(label,fontSize=10.sp,color=muted);Text(value,fontSize=17.sp,fontWeight=FontWeight.Bold)}}
@Composable private fun ArticleCard(a:Article,done:Boolean,open:()->Unit){val s=subjects.first{it.name==a.subject};Row(Modifier.fillMaxWidth().background(panel,RoundedCornerShape(16.dp)).clickable(onClick=open).padding(14.dp),verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(46.dp).background(s.tint.copy(alpha=.15f),RoundedCornerShape(13.dp)),contentAlignment=Alignment.Center){Text(s.emoji,fontSize=24.sp)};Column(Modifier.weight(1f).padding(horizontal=12.dp)){Text(a.title,fontWeight=FontWeight.SemiBold);Text(a.subject+" · 离线知识卡",fontSize=11.sp,color=s.tint);Text(a.summary,fontSize=12.sp,color=muted,maxLines=2)};if(done)Icon(Icons.Default.CheckCircle,null,tint=mint)else Icon(Icons.Default.ChevronRight,null,tint=muted)}}
@Composable private fun ArticlePage(db:Store,u:Long,a:Article,back:()->Unit,refresh:()->Unit){
 var note by remember(a.id){mutableStateOf(db.note(u,a.id))};var saved by remember(a.id){mutableStateOf(db.marked(u,a.id))};var answer by remember(a.id){mutableIntStateOf(-1)};var result by remember(a.id){mutableStateOf("")}
 val q=when(a.id){1->"恒星主要通过什么过程释放能量？";3->"四季变化的关键原因是什么？";9->"最小权限原则意味着什么？";10->"可疑邮件应如何处理？";11->"怎样更有效地学习词汇？";else->"阅读后最重要的学习方法是什么？"}
 val opts=when(a.id){1->listOf("化学燃烧","核聚变","反射行星光");3->listOf("地轴倾斜","月球引力","太阳距离变化");9->listOf("所有人都是管理员","只授予任务所需权限","共享所有密码");10->listOf("立刻点击链接","通过可信渠道独立确认","回复个人密码");11->listOf("只背翻译","在语境中练习并主动回忆","永不复习");else->listOf("读完就忘记","理解内容并主动回忆","只看标题")}
 val correct=if(a.id==3)0 else 1
 LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
 item{TextButton(onClick=back){Icon(Icons.Default.ArrowBack,null);Text(" 返回")};Text(a.subject.uppercase(),fontSize=11.sp,color=mint,letterSpacing=2.sp);Text(a.title,fontSize=29.sp,fontWeight=FontWeight.Bold);Text(a.summary,fontSize=17.sp,color=violet)}
 item{Text(a.body,fontSize=16.sp,lineHeight=28.sp)}
 item{Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){Button(onClick={db.record(u,a.id,true);refresh()},modifier=Modifier.weight(1f)){Icon(Icons.Default.CheckCircle,null);Text(if(db.isDone(u,a.id))"已学完" else "标记学完")};OutlinedButton(onClick={db.toggle(u,a.id);saved=!saved},modifier=Modifier.weight(1f)){Icon(if(saved)Icons.Default.Bookmark else Icons.Default.BookmarkBorder,null);Text(if(saved)"已收藏" else "收藏")}}}
 item{Text("个人笔记",fontSize=19.sp,fontWeight=FontWeight.Bold);OutlinedTextField(note,{note=it},modifier=Modifier.fillMaxWidth(),minLines=3,placeholder={Text("记录理解、问题或关联知识…")});Button(onClick={db.saveNote(u,a.id,note);refresh()},modifier=Modifier.fillMaxWidth()){Text("保存本地笔记")}}
 item{Text("快速测验",fontSize=19.sp,fontWeight=FontWeight.Bold);Text(q,color=muted)}
 items(opts){opt->Row(Modifier.fillMaxWidth().clickable{answer=opts.indexOf(opt)}.padding(4.dp),verticalAlignment=Alignment.CenterVertically){RadioButton(selected=answer==opts.indexOf(opt),onClick={answer=opts.indexOf(opt)});Text(opt)}}
 item{Button(onClick={if(answer>=0){val ok=answer==correct;db.quiz(u,a.id,ok);result=if(ok)"回答正确！继续保持 ✨" else "已记录本次作答，再复习一下知识点。";refresh()}},enabled=answer>=0,modifier=Modifier.fillMaxWidth()){Text("提交答案")};if(result.isNotBlank())Text(result,color=mint)}
 }
}
