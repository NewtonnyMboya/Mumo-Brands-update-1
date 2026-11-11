package com.mumobrands.printing

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.content.Intent
import android.net.Uri
import android.util.Base64
import java.security.MessageDigest
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import java.security.SecureRandom
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Navy = Color(0xFF171653)
private val Magenta = Color(0xFFD62983)
private val Ink = Color(0xFF20213A)
private val Soft = Color(0xFFF4F3FA)

class BusinessDb(context: Context) : SQLiteOpenHelper(context, "mumo_brands.db", null, 4) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE orders(id INTEGER PRIMARY KEY AUTOINCREMENT, customer TEXT NOT NULL, phone TEXT, item TEXT NOT NULL, size TEXT, color TEXT, qty INTEGER NOT NULL, service TEXT, due TEXT, total REAL NOT NULL DEFAULT 0, paid REAL NOT NULL DEFAULT 0, status TEXT NOT NULL DEFAULT 'Received', notes TEXT)")
        db.execSQL("CREATE TABLE stock(id INTEGER PRIMARY KEY AUTOINCREMENT, item TEXT NOT NULL, category TEXT, size TEXT, color TEXT, qty INTEGER NOT NULL DEFAULT 0, reorder INTEGER NOT NULL DEFAULT 5, cost REAL NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE customers(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, phone TEXT, email TEXT, notes TEXT)")
        db.execSQL("CREATE TABLE payments(id INTEGER PRIMARY KEY AUTOINCREMENT, order_id INTEGER, amount REAL, method TEXT, paid_at TEXT DEFAULT CURRENT_TIMESTAMP, transaction_ref TEXT NOT NULL DEFAULT '', status TEXT NOT NULL DEFAULT 'Recorded')")
        db.execSQL("CREATE TABLE stock_movements(id INTEGER PRIMARY KEY AUTOINCREMENT, stock_id INTEGER NOT NULL, movement TEXT NOT NULL, quantity INTEGER NOT NULL, reason TEXT, created_at TEXT DEFAULT CURRENT_TIMESTAMP)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("CREATE TABLE IF NOT EXISTS stock_movements(id INTEGER PRIMARY KEY AUTOINCREMENT, stock_id INTEGER NOT NULL, movement TEXT NOT NULL, quantity INTEGER NOT NULL, reason TEXT, created_at TEXT DEFAULT CURRENT_TIMESTAMP)")
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE payments ADD COLUMN transaction_ref TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE payments ADD COLUMN status TEXT NOT NULL DEFAULT 'Recorded'")
        }
        if (oldVersion < 4) db.execSQL("CREATE INDEX IF NOT EXISTS idx_payments_order_date ON payments(order_id, paid_at)")
    }
    fun addOrder(customer:String, phone:String, item:String, size:String, color:String, qty:Int, service:String, due:String, total:Double, paid:Double) {
        val v=ContentValues().apply { put("customer",customer);put("phone",phone);put("item",item);put("size",size);put("color",color);put("qty",qty);put("service",service);put("due",due);put("total",total);put("paid",paid) }
        require(total.isFinite() && paid.isFinite() && total >= 0.0 && paid >= 0.0 && paid <= total) { "Payment must be between zero and the order total" }
        val db=writableDatabase; db.beginTransaction()
        try {
            val orderId=db.insert("orders",null,v)
            if (orderId > 0 && paid > 0.0) {
                val p=ContentValues().apply { put("order_id",orderId);put("amount",paid);put("method","Opening deposit");put("transaction_ref","");put("status","Recorded") }
                db.insert("payments",null,p)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        val c=ContentValues().apply { put("name",customer);put("phone",phone) }
        writableDatabase.insertWithOnConflict("customers",null,c,SQLiteDatabase.CONFLICT_IGNORE)
    }
    fun recordPayment(orderId:Int, amount:Double, method:String, reference:String) {
        require(amount.isFinite() && amount > 0.0) { "Enter a payment greater than zero" }
        require(method.isNotBlank()) { "Select a payment method" }
        val db=writableDatabase; db.beginTransaction()
        try {
            val order=db.rawQuery("SELECT total,paid FROM orders WHERE id=?",arrayOf(orderId.toString())).use { c ->
                require(c.moveToFirst()) { "Order not found" }; Pair(c.getDouble(0),c.getDouble(1))
            }
            val balance=(order.first-order.second).coerceAtLeast(0.0)
            require(amount <= balance + 0.000001) { "Payment exceeds the outstanding balance" }
            val cleanReference=reference.trim()
            if (cleanReference.isNotEmpty()) {
                val duplicate=db.rawQuery("SELECT COUNT(*) FROM payments WHERE lower(method)=lower(?) AND lower(transaction_ref)=lower(?) AND trim(transaction_ref)<>''",arrayOf(method,cleanReference)).use { it.moveToFirst() && it.getInt(0)>0 }
                require(!duplicate) { "This transaction reference has already been recorded for $method" }
            }
            val p=ContentValues().apply { put("order_id",orderId);put("amount",amount);put("method",method);put("transaction_ref",cleanReference);put("status","Recorded") }
            db.insertOrThrow("payments",null,p)
            val update=ContentValues().apply { put("paid",order.second+amount) }
            db.update("orders",update,"id=?",arrayOf(orderId.toString()))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun paymentRows():List<List<String>> {
        val out=mutableListOf<List<String>>()
        readableDatabase.rawQuery("SELECT p.id,p.order_id,o.customer,p.amount,p.method,p.transaction_ref,p.status,p.paid_at FROM payments p LEFT JOIN orders o ON o.id=p.order_id ORDER BY p.id DESC",null).use { c ->
            while(c.moveToNext()) out.add((0 until c.columnCount).map { c.getString(it) ?: "" })
        }
        return out
    }
    fun addStock(item:String, category:String, size:String, color:String, qty:Int, reorder:Int) {
        val v=ContentValues().apply { put("item",item);put("category",category);put("size",size);put("color",color);put("qty",0);put("reorder",reorder) }
        val id=writableDatabase.insert("stock",null,v)
        if (id > 0 && qty > 0) moveStock(id, "IN", qty, "Opening stock")
    }
    fun updateOrder(id:Int, customer:String, phone:String, item:String, size:String, color:String, qty:Int, service:String, due:String, total:Double, paid:Double) {
        require(total.isFinite() && paid.isFinite() && total >= 0.0 && paid >= 0.0 && paid <= total) { "Paid amount cannot exceed the order total" }
        require(total.isFinite() && total >= 0.0) { "Order total must be a valid non-negative amount" }
        val db=writableDatabase
        db.beginTransaction()
        try {
            val recordedPaid=db.rawQuery("SELECT paid FROM orders WHERE id=?",arrayOf(id.toString())).use { c -> require(c.moveToFirst()) { "Order not found" }; c.getDouble(0) }
            require(total + 0.000001 >= recordedPaid) { "Order total cannot be less than payments already recorded. Record a refund or correction separately." }
            val v=ContentValues().apply { put("customer",customer);put("phone",phone);put("item",item);put("size",size);put("color",color);put("qty",qty);put("service",service);put("due",due);put("total",total);put("paid",recordedPaid) }
            db.update("orders",v,"id=?",arrayOf(id.toString()))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun updateStock(id:Int,item:String,category:String,size:String,color:String,reorder:Int) { val v=ContentValues().apply { put("item",item);put("category",category);put("size",size);put("color",color);put("reorder",reorder) }; writableDatabase.update("stock",v,"id=?",arrayOf(id.toString())) }
    fun deleteRecord(table:String,id:Int) { require(table in listOf("orders","stock","customers")); writableDatabase.delete(table,"id=?",arrayOf(id.toString())) }
    fun moveStock(id:Long,movement:String,quantity:Int,reason:String) {
        require(movement in listOf("IN","OUT","ADJUST") && quantity>0)
        val db=writableDatabase; db.beginTransaction()
        try {
            val current=db.rawQuery("SELECT qty FROM stock WHERE id=?",arrayOf(id.toString())).use { if(it.moveToFirst()) it.getInt(0) else -1 }
            require(current>=0)
            val next=when(movement){"IN"->current+quantity;"OUT"->current-quantity;else->quantity}
            require(next>=0) { "Stock cannot become negative" }
            val v=ContentValues().apply { put("qty",next) }; db.update("stock",v,"id=?",arrayOf(id.toString()))
            val m=ContentValues().apply { put("stock_id",id);put("movement",movement);put("quantity",quantity);put("reason",reason) }; db.insert("stock_movements",null,m)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun orderDetails(id:Int):List<String>? = readableDatabase.rawQuery("SELECT id,customer,phone,item,size,color,qty,service,due,total,paid,status FROM orders WHERE id=?",arrayOf(id.toString())).use { c -> if(c.moveToFirst()) (0 until c.columnCount).map { c.getString(it) ?: "" } else null }
    fun stockDetails(id:Int):List<String>? = readableDatabase.rawQuery("SELECT id,item,category,size,color,qty,reorder FROM stock WHERE id=?",arrayOf(id.toString())).use { c -> if(c.moveToFirst()) (0 until c.columnCount).map { c.getString(it) ?: "" } else null }
    fun movements():List<List<String>> { val out=mutableListOf<List<String>>(); readableDatabase.rawQuery("SELECT m.id,s.item,m.movement,m.quantity,m.reason,m.created_at FROM stock_movements m LEFT JOIN stock s ON s.id=m.stock_id ORDER BY m.id DESC",null).use { c -> while(c.moveToNext()) out.add((0 until c.columnCount).map { c.getString(it) ?: "" }) }; return out }
    fun csv(table:String):String { require(table in listOf("orders","stock","customers","stock_movements","payments")); val out=StringBuilder(); readableDatabase.rawQuery("SELECT * FROM $table ORDER BY id",null).use { c -> out.append((0 until c.columnCount).joinToString(",") { csvCell(c.getColumnName(it)) }).append('\n'); while(c.moveToNext()) out.append((0 until c.columnCount).joinToString(",") { csvCell(c.getString(it) ?: "") }).append('\n') }; return out.toString() }
    private fun csvCell(v:String)="\""+v.replace("\"","\"\"")+"\""
    fun rows(table:String):List<List<String>> {
        val out= mutableListOf<List<String>>()
        readableDatabase.rawQuery("SELECT * FROM $table ORDER BY id DESC",null).use { c -> while(c.moveToNext()) {
            when(table) {
                "orders" -> out.add(listOf(c.getString(c.getColumnIndexOrThrow("id")),c.getString(c.getColumnIndexOrThrow("customer")),c.getString(c.getColumnIndexOrThrow("item")),c.getString(c.getColumnIndexOrThrow("qty")),c.getString(c.getColumnIndexOrThrow("status")),c.getString(c.getColumnIndexOrThrow("total")),c.getString(c.getColumnIndexOrThrow("paid"))))
                "stock" -> out.add(listOf(c.getString(c.getColumnIndexOrThrow("id")),c.getString(c.getColumnIndexOrThrow("item")),c.getString(c.getColumnIndexOrThrow("category")),c.getString(c.getColumnIndexOrThrow("size")),c.getString(c.getColumnIndexOrThrow("color")),c.getString(c.getColumnIndexOrThrow("qty")),c.getString(c.getColumnIndexOrThrow("reorder"))))
                else -> out.add(listOf(c.getString(c.getColumnIndexOrThrow("id")),c.getString(c.getColumnIndexOrThrow("name")),c.getString(c.getColumnIndexOrThrow("phone"))))
            }
        } }
        return out
    }
    fun count(table:String):Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM $table",null).use { if(it.moveToFirst()) it.getInt(0) else 0 }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); val db=BusinessDb(this); setContent { AuthGate(this) { MumoApp(db, this) } } }
}

@Composable fun AuthGate(context:Context, content:@Composable ()->Unit) {
    val prefs=context.getSharedPreferences("mumo_auth",Context.MODE_PRIVATE)
    var configured by remember { mutableStateOf(prefs.contains("pin_hash")) }
    var unlocked by remember { mutableStateOf(false) }
    var pin by remember { mutableStateOf("") }; var confirm by remember { mutableStateOf("") }; var error by remember { mutableStateOf("") }
    if (unlocked) content() else Surface(Modifier.fillMaxSize(),color=Soft) { Column(Modifier.fillMaxSize().padding(26.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) {
        Text("MB",fontSize=48.sp,fontWeight=FontWeight.Black,color=Magenta); Text("MUMO BRANDS",fontWeight=FontWeight.Bold,color=Navy,fontSize=22.sp); Text(if(configured) "Staff sign in" else "Create owner PIN",color=Ink)
        Spacer(Modifier.height(18.dp)); OutlinedTextField(pin,{pin=it.filter(Char::isDigit).take(8)},label={Text("4–8 digit PIN")},singleLine=true)
        if(!configured) OutlinedTextField(confirm,{confirm=it.filter(Char::isDigit).take(8)},label={Text("Confirm PIN")},singleLine=true)
        if(error.isNotBlank()) Text(error,color=Magenta)
        Button(onClick={ if(pin.length !in 4..8) error="Enter a PIN with 4 to 8 digits" else if(!configured && pin!=confirm) error="PINs do not match" else if(!configured) { val salt=ByteArray(16).also{SecureRandom().nextBytes(it)}; prefs.edit().putString("salt",Base64.encodeToString(salt,Base64.NO_WRAP)).putString("pin_hash",pinDigest(pin,salt)).apply();configured=true;unlocked=true } else { val salt=Base64.decode(prefs.getString("salt","")?:"",Base64.DEFAULT); if(pinDigest(pin,salt)==prefs.getString("pin_hash",null)) unlocked=true else error="Incorrect PIN" }; pin="";confirm="" },colors=ButtonDefaults.buttonColors(containerColor=Magenta)) { Text(if(configured) "Unlock" else "Save PIN") }
        Text("Your business records remain stored locally on this device.",fontSize=11.sp,color=Ink.copy(alpha=.6f))
    } }
}
fun pinDigest(pin:String,salt:ByteArray):String { val spec=PBEKeySpec(pin.toCharArray(),salt,120000,256); return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded.joinToString(""){"%02x".format(it)} }

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun MumoApp(db:BusinessDb, activity:MainActivity) {
    var page by remember { mutableStateOf("Dashboard") }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var showOrder by remember { mutableStateOf(false) }
    var showStock by remember { mutableStateOf(false) }
    val appearancePrefs=activity.getSharedPreferences("mumo_appearance",Context.MODE_PRIVATE)
    var darkMode by remember { mutableStateOf(appearancePrefs.getBoolean("dark_mode",false)) }
    val menu=listOf("Dashboard","New Orders","Customers","Clothing Inventory","Printing Services","Payments","Stock Movements","Receipts & Invoices","Reports",if(darkMode) "Switch to Light Mode" else "Switch to Dark Mode","Settings")
    MaterialTheme(colorScheme=if(darkMode) darkColorScheme(primary=Magenta,secondary=Navy,background=Color(0xFF121212),surface=Color(0xFF1E1E1E),onBackground=Color.White,onSurface=Color.White) else lightColorScheme(primary=Magenta,secondary=Navy,background=Soft,surface=Color.White)) {
    ModalNavigationDrawer(drawerState=drawerState, gesturesEnabled=true,
        drawerContent={ ModalDrawerSheet {
            Column(Modifier.fillMaxWidth().background(Navy).padding(20.dp)) {
                Text("MB",color=Magenta,fontSize=38.sp,fontWeight=FontWeight.Black)
                Text("MUMO BRANDS",color=Color.White,fontWeight=FontWeight.Bold)
                Text("Printing & Branding Services",color=Color.White.copy(alpha=.8f),fontSize=12.sp)
            }
            menu.forEach { label -> NavigationDrawerItem(label= { Text(label) }, selected=page==label, onClick={if(label=="Switch to Light Mode" || label=="Switch to Dark Mode"){darkMode=!darkMode;appearancePrefs.edit().putBoolean("dark_mode",darkMode).apply()}else page=label;scope.launch { drawerState.close() }}, icon={Icon(menuIcon(label),null)}, modifier=Modifier.padding(horizontal=10.dp)) }
        } },
        content={
            Scaffold(topBar={ TopAppBar(title={ Column { Text("MUMO BRANDS",fontWeight=FontWeight.Bold,color=Color.White);Text(page,fontSize=12.sp,color=Color.White.copy(alpha=.8f)) } }, navigationIcon={ IconButton(onClick={scope.launch { drawerState.open() }}) { Icon(Icons.Default.Menu,"Home menu",tint=Color.White) } }, colors=TopAppBarDefaults.topAppBarColors(containerColor=Navy)) },
                bottomBar={ Column(Modifier.fillMaxWidth().background(Navy).padding(vertical=9.dp), horizontalAlignment=Alignment.CenterHorizontally) {
                    Text("MUMO BRANDS  •  Quality and perfection",color=Color.White,fontWeight=FontWeight.SemiBold,fontSize=12.sp)
                    Text("☎ 0741 167 612   •   Bazaar House (Kwa Kaswii), Kitui Stage, Kibwezi Town",color=Color.White,fontSize=10.sp)
                    Text("Infinity Cyber Technologies",color=Color.White,fontSize=10.sp,fontWeight=FontWeight.SemiBold)
                    Text("infinitycybertech@gmail.com  •  +254740889001",color=Color.White,fontSize=9.sp)
                } }) { pad ->
                Box(Modifier.padding(pad).fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    when(page) {
                        "Dashboard" -> Dashboard(db,refresh,{showOrder=true},{showStock=true},{page=it})
                        "New Orders" -> OrderRecordsPage(db,refresh,{showOrder=true},{id-> printOrder(activity,db.orderDetails(id)) },{id->db.deleteRecord("orders",id);refresh++})
                        "Clothing Inventory" -> StockRecordsPage(db,refresh,{showStock=true},{id,move,qty,reason->db.moveStock(id.toLong(),move,qty,reason);refresh++},{id->db.deleteRecord("stock",id);refresh++})
                        "Stock Movements" -> MovementPage(db,refresh)
                        "Customers" -> RecordsPage("Customer directory",db.rows("customers"),listOf("ID","Customer","Phone"),{})
                        "Printing Services" -> ServicesPage()
                        "Payments" -> PaymentsPage(db,refresh,{refresh++})
                        "Receipts & Invoices" -> OrderRecordsPage(db,refresh,{showOrder=true},{id->printOrder(activity,db.orderDetails(id))},{id->db.deleteRecord("orders",id);refresh++},true)
                        "Reports" -> ExportPage(db,activity)
                        "Settings" -> SecuritySettings(activity)
                        else -> InfoPage(page,"Business records are stored in the private on-device database.")
                    }
                }
            }
        }
    )
    }
    if(showOrder) OrderDialog({showOrder=false},{ c,p,i,s,col,q,svc,due,total,paid -> db.addOrder(c,p,i,s,col,q,svc,due,total,paid);refresh++ })
    if(showStock) StockDialog({showStock=false},{i,cat,s,col,q,r->db.addStock(i,cat,s,col,q,r);refresh++})
}

@Composable fun Dashboard(db:BusinessDb, refresh:Int, newOrder:()->Unit, addStock:()->Unit, navigate:(String)->Unit) {
    val orders=db.count("orders"); val stock=db.count("stock"); val customers=db.count("customers")
    LazyColumn(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item { Card(shape=RoundedCornerShape(22.dp),colors=CardDefaults.cardColors(containerColor=Navy)) { Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("MUMO BRANDS",color=Color.White,fontWeight=FontWeight.Black,fontSize=20.sp);Text("Quality and perfection",color=Color.White.copy(alpha=.8f),fontSize=12.sp) }; Image(painterResource(R.drawable.company_logo),contentDescription="Mumo Brands company logo",contentScale=ContentScale.Crop,modifier=Modifier.size(76.dp).background(Color.White,RoundedCornerShape(12.dp))) }
            Spacer(Modifier.height(18.dp));Text("Business command centre",color=Color.White,fontSize=22.sp,fontWeight=FontWeight.Bold);Text("Manage customers, production, garments and stock",color=Color.White.copy(alpha=.8f),fontSize=12.sp)
        } } }
        item { Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) { StatCard("Orders",orders.toString(),Icons.Default.ReceiptLong,Modifier.weight(1f));StatCard("Stock items",stock.toString(),Icons.Default.Inventory2,Modifier.weight(1f)) } }
        item { Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) { StatCard("Customers",customers.toString(),Icons.Default.People,Modifier.weight(1f));StatCard("Storage","Offline",Icons.Default.Lock,Modifier.weight(1f)) } }
        item { Text("Quick actions",fontSize=18.sp,fontWeight=FontWeight.Bold,color=Ink) }
        item { Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) { ActionCard("Create order",Icons.Default.AddShoppingCart,Modifier.weight(1f),newOrder);ActionCard("Add stock",Icons.Default.AddBox,Modifier.weight(1f),addStock) } }
        item { Text("Business modules",fontSize=18.sp,fontWeight=FontWeight.Bold,color=Ink) }
        items(listOf("New Orders","Customers","Clothing Inventory","Printing Services","Payments","Reports")) { label -> Card(Modifier.fillMaxWidth().clickable{navigate(label)},colors=CardDefaults.cardColors(containerColor=Color.White)) { Row(Modifier.padding(15.dp),verticalAlignment=Alignment.CenterVertically) { Icon(menuIcon(label),null,tint=Magenta);Spacer(Modifier.width(12.dp));Text(label,Modifier.weight(1f),fontWeight=FontWeight.SemiBold);Icon(Icons.Default.ChevronRight,null) } } }
    }
}
@Composable fun StatCard(label:String,value:String,icon:androidx.compose.ui.graphics.vector.ImageVector,modifier:Modifier=Modifier) { Card(modifier,colors=CardDefaults.cardColors(containerColor=Color.White),shape=RoundedCornerShape(18.dp)) { Column(Modifier.padding(14.dp)) { Icon(icon,null,tint=Magenta);Text(value,fontSize=24.sp,fontWeight=FontWeight.Bold,color=Navy);Text(label,color=Ink.copy(alpha=.65f),fontSize=12.sp) } } }
@Composable fun ActionCard(label:String,icon:androidx.compose.ui.graphics.vector.ImageVector,modifier:Modifier=Modifier,click:()->Unit) { Card(modifier.clickable{click()},colors=CardDefaults.cardColors(containerColor=Color.White),shape=RoundedCornerShape(18.dp)) { Column(Modifier.padding(16.dp)) { Icon(icon,null,tint=Magenta);Spacer(Modifier.height(8.dp));Text(label,fontWeight=FontWeight.Bold,color=Ink) } } }
@Composable fun RecordsPage(title:String, rows:List<List<String>>, headers:List<String>, add:(()->Unit)?=null) { Column(Modifier.fillMaxSize().padding(16.dp)) { Row(verticalAlignment=Alignment.CenterVertically) { Text(title,Modifier.weight(1f),fontSize=20.sp,fontWeight=FontWeight.Bold); if(add!=null) FilledTonalButton(onClick=add){Icon(Icons.Default.Add,null);Text("Add")} };Spacer(Modifier.height(10.dp));if(rows.isEmpty()) EmptyState("No records yet","Use the add action or create your first order.") else LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)){items(rows){r->Card(colors=CardDefaults.cardColors(containerColor=Color.White)){Column(Modifier.padding(13.dp)){Text("${headers.getOrElse(1){"Record"}}: ${r.getOrElse(1){""}}",fontWeight=FontWeight.Bold);Text(r.drop(2).joinToString("  •  "),fontSize=12.sp,color=Ink.copy(alpha=.7f))}}}} } }
@Composable fun ServicesPage(){ val services=listOf("T-shirt printing","Jersey printing","Cup branding","Water bottle branding","Custom stickers","Local jerseys","International jerseys","Banners and flyers","Logos and brochures","Business cards");LazyColumn(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){item{Text("Available services",fontSize=20.sp,fontWeight=FontWeight.Bold)};items(services){Card(colors=CardDefaults.cardColors(containerColor=Color.White)){Row(Modifier.padding(15.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.Print,null,tint=Magenta);Spacer(Modifier.width(12.dp));Text(it,fontWeight=FontWeight.SemiBold)}}}} }
@Composable fun PaymentsPage(db:BusinessDb,refresh:Int,onRefresh:()->Unit){
    var selected by remember{mutableIntStateOf(0)}
    var amount by remember{mutableStateOf("")};var method by remember{mutableStateOf("M-PESA")};var reference by remember{mutableStateOf("")};var error by remember{mutableStateOf("")}
    val orders=db.rows("orders")
    val totals=orders.mapNotNull{r->val total=r.getOrNull(5)?.toDoubleOrNull();val paid=r.getOrNull(6)?.toDoubleOrNull();if(total!=null&&paid!=null)Pair(total,paid)else null}
    val total=totals.sumOf{it.first};val paid=totals.sumOf{it.second};val balance=(total-paid).coerceAtLeast(0.0)
    Column(Modifier.fillMaxSize().padding(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
        Text("Payments & reconciliation",fontSize=20.sp,fontWeight=FontWeight.Bold)
        Text("KES • All entries below are recorded payments, not live M-PESA or bank transactions. Verify the provider receipt before saving.",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurface.copy(alpha=.7f))
        StatCard("Order value","KES %.2f".format(total),Icons.Default.Payments)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){StatCard("Paid","KES %.2f".format(paid),Icons.Default.AccountBalanceWallet,Modifier.weight(1f));StatCard("Balance","KES %.2f".format(balance),Icons.Default.PendingActions,Modifier.weight(1f))}
        Text("Record a payment",fontWeight=FontWeight.Bold)
        if(orders.isEmpty()) EmptyState("No orders to pay","Create an order first.") else LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)){
            items(orders){r->val id=r[0].toInt();val orderTotal=r.getOrNull(5)?.toDoubleOrNull()?:0.0;val orderPaid=r.getOrNull(6)?.toDoubleOrNull()?:0.0;val due=(orderTotal-orderPaid).coerceAtLeast(0.0)
                Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)){Column(Modifier.padding(12.dp)){Text("#$id  ${r[1]}",fontWeight=FontWeight.Bold);Text("Total KES %.2f  •  Paid KES %.2f  •  Due KES %.2f".format(orderTotal,orderPaid,due),fontSize=12.sp);if(due>0)TextButton(onClick={selected=id;amount="";reference="";error=""}){Text("Record payment")}else Text("Fully paid",color=Magenta)}}
            }
            item{Divider();Text("Payment history",fontWeight=FontWeight.Bold)}
            items(db.paymentRows()){p->Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)){Column(Modifier.padding(10.dp)){Text("Order #${p[1]} • ${p[2]}",fontWeight=FontWeight.SemiBold);Text("KES ${p[3]} • ${p[4]} • Ref: ${p[5].ifBlank{"—"}}",fontSize=12.sp);Text("${p[6]} • ${p[7]}",fontSize=11.sp)}}}
        }
    }
    if(selected>0) AlertDialog(onDismissRequest={selected=0},title={Text("Record payment for order #$selected")},text={Column(verticalArrangement=Arrangement.spacedBy(7.dp)){Field("Amount (KES)",amount){amount=it};Text("Payment method (record only)");Row{listOf("M-PESA","Cash","Bank","Card","Other").forEach{FilterChip(selected=method==it,onClick={method=it},label={Text(it)})}};Field("Provider receipt / transaction reference",reference){reference=it};Text("For M-PESA, enter the exact receipt code only after confirming the payment in the official provider channel.",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurface.copy(alpha=.7f));if(error.isNotBlank())Text(error,color=Magenta)}},confirmButton={Button(onClick={try{db.recordPayment(selected,amount.toDoubleOrNull()?:Double.NaN,method,reference);selected=0;onRefresh()}catch(e:Exception){error=e.message?: "Payment could not be saved"}},colors=ButtonDefaults.buttonColors(containerColor=Magenta)){Text("Save payment")}},dismissButton={TextButton(onClick={selected=0}){Text("Cancel")}})
}
@Composable fun InfoPage(title:String,body:String){Column(Modifier.padding(20.dp)){Text(title,fontSize=21.sp,fontWeight=FontWeight.Bold);Spacer(Modifier.height(12.dp));Text(body,color=Ink.copy(alpha=.75f))}}
@Composable fun EmptyState(title:String,body:String){Column(Modifier.fillMaxWidth().padding(28.dp),horizontalAlignment=Alignment.CenterHorizontally){Icon(Icons.Default.Inventory2,null,tint=Magenta,modifier=Modifier.size(38.dp));Text(title,fontWeight=FontWeight.Bold);Text(body,fontSize=12.sp,color=Ink.copy(alpha=.6f))}}
@Composable fun OrderDialog(close:()->Unit,save:(String,String,String,String,String,Int,String,String,Double,Double)->Unit){var customer by remember{mutableStateOf("")};var phone by remember{mutableStateOf("")};var item by remember{mutableStateOf("T-shirt")};var size by remember{mutableStateOf("M")};var color by remember{mutableStateOf("")};var qty by remember{mutableStateOf("1")};var service by remember{mutableStateOf("T-shirt printing")};var due by remember{mutableStateOf("")};var total by remember{mutableStateOf("0")};var paid by remember{mutableStateOf("0")};AlertDialog(onDismissRequest=close,title={Text("Create customer order")},text={Column(verticalArrangement=Arrangement.spacedBy(6.dp)){Field("Customer name",customer){customer=it};Field("Phone number",phone){phone=it};Field("Garment / product",item){item=it};Field("Size",size){size=it};Field("Colour",color){color=it};Field("Quantity",qty){qty=it};Field("Branding service",service){service=it};Field("Due date",due){due=it};Field("Total (KES)",total){total=it};Field("Deposit paid (KES)",paid){paid=it}}},confirmButton={Button(onClick={if(customer.isNotBlank()&&item.isNotBlank()){save(customer,phone,item,size,color,qty.toIntOrNull()?.coerceAtLeast(1)?:1,service,due,total.toDoubleOrNull()?:0.0,paid.toDoubleOrNull()?:0.0);close()}},colors=ButtonDefaults.buttonColors(containerColor=Magenta)){Text("Save order")} },dismissButton={TextButton(onClick=close){Text("Cancel")}}) }
@Composable fun StockDialog(close:()->Unit,save:(String,String,String,String,Int,Int)->Unit){var item by remember{mutableStateOf("")};var cat by remember{mutableStateOf("T-shirts")};var size by remember{mutableStateOf("M")};var color by remember{mutableStateOf("")};var qty by remember{mutableStateOf("0")};var reorder by remember{mutableStateOf("5")};AlertDialog(onDismissRequest=close,title={Text("Add inventory item")},text={Column(verticalArrangement=Arrangement.spacedBy(6.dp)){Field("Item name",item){item=it};Field("Category",cat){cat=it};Field("Size",size){size=it};Field("Colour",color){color=it};Field("Quantity",qty){qty=it};Field("Low-stock threshold",reorder){reorder=it}}},confirmButton={Button(onClick={if(item.isNotBlank()){save(item,cat,size,color,qty.toIntOrNull()?:0,reorder.toIntOrNull()?:5);close()}},colors=ButtonDefaults.buttonColors(containerColor=Magenta)){Text("Save stock")}},dismissButton={TextButton(onClick=close){Text("Cancel")}})}
@Composable fun Field(label:String,value:String,onChange:(String)->Unit){OutlinedTextField(value,onChange,label={Text(label)},singleLine=true,modifier=Modifier.fillMaxWidth())}
@Composable fun OrderRecordsPage(db:BusinessDb,refresh:Int,add:()->Unit,print:(Int)->Unit,delete:(Int)->Unit,showAll:Boolean=false) {
    var edit by remember { mutableStateOf<List<String>?>(null) }
    var deleteId by remember { mutableIntStateOf(0) }
    val rows=db.rows("orders")
    Column(Modifier.fillMaxSize().padding(14.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            Text(if(showAll) "Receipts & invoices" else "Customer orders",Modifier.weight(1f),fontWeight=FontWeight.Bold,fontSize=20.sp)
            Button(onClick=add,colors=ButtonDefaults.buttonColors(containerColor=Magenta)){Text("New order")}
        }
        if(rows.isEmpty()) {
            EmptyState("No orders yet","Create a customer order to begin.")
        } else {
            LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                items(rows) { r ->
                    val id=r[0].toInt()
                    Card(colors=CardDefaults.cardColors(containerColor=Color.White)) {
                        Column(Modifier.padding(12.dp)) {
                            Text("#${r[0]}  ${r[1]}",fontWeight=FontWeight.Bold,color=Navy)
                            Text("${r[2]} • Qty ${r[3]} • ${r[4]}",fontSize=12.sp)
                            Text("Total KES ${r[5]}  |  Paid KES ${r[6]}",fontSize=12.sp)
                            Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick={edit=db.orderDetails(id)}) { Text("Edit") }
                                TextButton(onClick={print(id)}) { Text("Print") }
                                TextButton(onClick={deleteId=id}) { Text("Delete",color=Magenta) }
                            }
                        }
                    }
                }
            }
        }
    }
    edit?.let { d ->
        OrderEditDialog(d,{edit=null}) { c,p,item,size,color,qty,service,due,total,paid ->
            db.updateOrder(d[0].toInt(),c,p,item,size,color,qty,service,due,total,paid)
            edit=null; refresh++
        }
    }
    if(deleteId>0) AlertDialog(onDismissRequest={deleteId=0},title={Text("Delete order?")},text={Text("This permanently deletes the selected order record.")},confirmButton={TextButton(onClick={delete(deleteId);deleteId=0}){Text("Delete",color=Magenta)}},dismissButton={TextButton(onClick={deleteId=0}){Text("Cancel")}})
}

@Composable fun OrderEditDialog(d:List<String>,close:()->Unit,save:(String,String,String,String,String,Int,String,String,Double,Double)->Unit) {
    var c by remember{mutableStateOf(d[1])};var p by remember{mutableStateOf(d[2])};var item by remember{mutableStateOf(d[3])};var size by remember{mutableStateOf(d[4])};var color by remember{mutableStateOf(d[5])};var qty by remember{mutableStateOf(d[6])};var service by remember{mutableStateOf(d[7])};var due by remember{mutableStateOf(d[8])};var total by remember{mutableStateOf(d[9])};var paid by remember{mutableStateOf(d[10])}
    AlertDialog(onDismissRequest=close,title={Text("Edit order #${d[0]}")},text={Column(Modifier.heightIn(max=440.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){Field("Customer",c){c=it};Field("Phone",p){p=it};Field("Product",item){item=it};Field("Size",size){size=it};Field("Colour",color){color=it};Field("Quantity",qty){qty=it};Field("Service",service){service=it};Field("Due date",due){due=it};Field("Total KES",total){total=it};Text("Payments already recorded: KES $paid",fontWeight=FontWeight.SemiBold,color=Magenta);Text("To add a deposit or settle the balance, use the Payments menu. Editing an order will not rewrite its payment history.",fontSize=11.sp,color=Ink.copy(alpha=.7f))}},confirmButton={Button(onClick={save(c,p,item,size,color,qty.toIntOrNull()?:1,service,due,total.toDoubleOrNull()?:0.0,paid.toDoubleOrNull()?:0.0)},colors=ButtonDefaults.buttonColors(containerColor=Magenta)){Text("Save changes")}},dismissButton={TextButton(onClick=close){Text("Cancel")}})
}

@Composable fun StockRecordsPage(db:BusinessDb,refresh:Int,add:()->Unit,move:(Int,String,Int,String)->Unit,delete:(Int)->Unit) {
    var moveId by remember{mutableIntStateOf(0)};var editId by remember{mutableIntStateOf(0)};var deleteId by remember{mutableIntStateOf(0)}
    Column(Modifier.fillMaxSize().padding(14.dp)){Row(verticalAlignment=Alignment.CenterVertically){Text("Clothing & materials",Modifier.weight(1f),fontWeight=FontWeight.Bold,fontSize=19.sp);Button(onClick=add,colors=ButtonDefaults.buttonColors(containerColor=Magenta)){Text("Add stock")}}
        LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)){items(db.rows("stock")){r->Card(colors=CardDefaults.cardColors(containerColor=Color.White)){Column(Modifier.padding(12.dp)){Text("${r[1]} • ${r[2]}",fontWeight=FontWeight.Bold);Text("Size ${r[3]} • ${r[4]} • Available ${r[5]} • Reorder at ${r[6]}",fontSize=12.sp);Row { TextButton(onClick={editId=r[0].toInt()}){Text("Edit")};TextButton(onClick={moveId=r[0].toInt()}){Text("Stock movement")};TextButton(onClick={deleteId=r[0].toInt()}){Text("Delete",color=Magenta)} }}}}}
    }
    if(moveId>0) StockMoveDialog({moveId=0}){kind,q,reason->move(moveId,kind,q,reason);moveId=0}
    if(editId>0) db.stockDetails(editId)?.let{d->StockEditDialog(d,{editId=0}){name,cat,size,color,reorder->db.updateStock(editId,name,cat,size,color,reorder);editId=0}}
    if(deleteId>0) AlertDialog(onDismissRequest={deleteId=0},title={Text("Delete stock item?")},text={Text("This removes the stock item record. Existing movement history is retained.")},confirmButton={TextButton(onClick={delete(deleteId);deleteId=0}){Text("Delete",color=Magenta)}},dismissButton={TextButton(onClick={deleteId=0}){Text("Cancel")}})
}
@Composable fun StockMoveDialog(close:()->Unit,save:(String,Int,String)->Unit){var kind by remember{mutableStateOf("IN")};var qty by remember{mutableStateOf("1")};var reason by remember{mutableStateOf("")};AlertDialog(onDismissRequest=close,title={Text("Record stock movement")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){Text("IN adds stock • OUT issues stock • ADJUST sets the new balance");Row{listOf("IN","OUT","ADJUST").forEach{FilterChip(selected=kind==it,onClick={kind=it},label={Text(it)})}};Field("Quantity",qty){qty=it};Field("Reason / reference",reason){reason=it}}},confirmButton={Button(onClick={val n=qty.toIntOrNull()?:0;if(n>0)save(kind,n,reason)}){Text("Record movement")}},dismissButton={TextButton(onClick=close){Text("Cancel")}})}
@Composable fun StockEditDialog(d:List<String>,close:()->Unit,save:(String,String,String,String,Int)->Unit){var item by remember{mutableStateOf(d[1])};var cat by remember{mutableStateOf(d[2])};var size by remember{mutableStateOf(d[3])};var color by remember{mutableStateOf(d[4])};var reorder by remember{mutableStateOf(d[6])};AlertDialog(onDismissRequest=close,title={Text("Edit stock item")},text={Column{Field("Item",item){item=it};Field("Category",cat){cat=it};Field("Size",size){size=it};Field("Colour",color){color=it};Field("Reorder threshold",reorder){reorder=it}}},confirmButton={Button(onClick={save(item,cat,size,color,reorder.toIntOrNull()?:5)}){Text("Save")}},dismissButton={TextButton(onClick=close){Text("Cancel")}})}
@Composable fun MovementPage(db:BusinessDb,refresh:Int){LazyColumn(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){item{Text("Stock movement audit",fontSize=20.sp,fontWeight=FontWeight.Bold)};items(db.movements()){r->Card(colors=CardDefaults.cardColors(containerColor=Color.White)){Column(Modifier.padding(12.dp)){Text("${r[1]} • ${r[2]} ${r[3]}",fontWeight=FontWeight.Bold);Text("${r[4]} • ${r[5]}",fontSize=12.sp)}}}}}

@Composable fun SecuritySettings(activity:MainActivity){
    var old by remember{mutableStateOf("")};var fresh by remember{mutableStateOf("")};var confirm by remember{mutableStateOf("")};var message by remember{mutableStateOf("")}
    val prefs=activity.getSharedPreferences("mumo_auth",Context.MODE_PRIVATE)
    Column(Modifier.fillMaxSize().padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){Text("Security settings",fontSize=22.sp,fontWeight=FontWeight.Bold);Text("Change the local staff access PIN. Use a PIN known only to authorized staff.",color=Ink.copy(alpha=.7f));Field("Current PIN",old){old=it};Field("New PIN (4–8 digits)",fresh){fresh=it};Field("Confirm new PIN",confirm){confirm=it};Button(onClick={val salt=Base64.decode(prefs.getString("salt","")?:"",Base64.DEFAULT);if(pinDigest(old,salt)!=prefs.getString("pin_hash",null))message="Current PIN is incorrect" else if(fresh.length !in 4..8 || fresh.any{!it.isDigit()})message="New PIN must contain 4–8 digits" else if(fresh!=confirm)message="PIN confirmation does not match" else {val nextSalt=ByteArray(16).also{SecureRandom().nextBytes(it)};prefs.edit().putString("salt",Base64.encodeToString(nextSalt,Base64.NO_WRAP)).putString("pin_hash",pinDigest(fresh,nextSalt)).apply();message="PIN updated successfully";old="";fresh="";confirm=""}},colors=ButtonDefaults.buttonColors(containerColor=Magenta)){Text("Update access PIN")};if(message.isNotBlank())Text(message,color=Magenta)}
}

@Composable fun ExportPage(db:BusinessDb,activity:MainActivity){
    val csvLauncher=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")){uri->if(uri!=null) activity.contentResolver.openOutputStream(uri)?.use{it.write(db.csv("orders").toByteArray())}}
    val stockCsvLauncher=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")){uri->if(uri!=null) activity.contentResolver.openOutputStream(uri)?.use{it.write(db.csv("stock").toByteArray())}}
    val movementCsvLauncher=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")){uri->if(uri!=null) activity.contentResolver.openOutputStream(uri)?.use{it.write(db.csv("stock_movements").toByteArray())}}
    val paymentsCsvLauncher=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")){uri->if(uri!=null) activity.contentResolver.openOutputStream(uri)?.use{it.write(db.csv("payments").toByteArray())}}
    val backupLauncher=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")){uri->if(uri!=null) try{db.readableDatabase.rawQuery("PRAGMA wal_checkpoint(FULL)",null).close();activity.contentResolver.openOutputStream(uri)?.use{out->FileInputStream(db.readableDatabase.path).use{input->input.copyTo(out)}}}catch(e:Exception){android.widget.Toast.makeText(activity,"Backup failed: ${e.message}",android.widget.Toast.LENGTH_LONG).show()}}
    Column(Modifier.fillMaxSize().padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){Text("Data backup & export",fontSize=22.sp,fontWeight=FontWeight.Bold);Text("Create a restorable SQLite database backup or export order data as CSV. Store backup files somewhere secure.",color=Ink.copy(alpha=.7f));Button(onClick={backupLauncher.launch("mumo-brands-backup.db")},colors=ButtonDefaults.buttonColors(containerColor=Navy),modifier=Modifier.fillMaxWidth()){Icon(Icons.Default.Backup,null);Spacer(Modifier.width(8.dp));Text("Export full database backup")};OutlinedButton(onClick={csvLauncher.launch("mumo-brands-orders.csv")},modifier=Modifier.fillMaxWidth()){Icon(Icons.Default.TableChart,null);Spacer(Modifier.width(8.dp));Text("Export orders as CSV")};OutlinedButton(onClick={stockCsvLauncher.launch("mumo-brands-stock.csv")},modifier=Modifier.fillMaxWidth()){Icon(Icons.Default.Inventory2,null);Spacer(Modifier.width(8.dp));Text("Export stock as CSV")};OutlinedButton(onClick={movementCsvLauncher.launch("mumo-brands-stock-movements.csv")},modifier=Modifier.fillMaxWidth()){Icon(Icons.Default.SwapHoriz,null);Spacer(Modifier.width(8.dp));Text("Export stock movement history")};OutlinedButton(onClick={paymentsCsvLauncher.launch("mumo-brands-payments.csv")},modifier=Modifier.fillMaxWidth()){Icon(Icons.Default.Payments,null);Spacer(Modifier.width(8.dp));Text("Export payment ledger as CSV")};Text("Database backup contains customer names, phone numbers, orders and financial records. Protect exported files.",fontSize=12.sp,color=Magenta)}
}

fun printOrder(activity:MainActivity,d:List<String>?){if(d==null)return;val html="""<html><body style='font-family:Arial;padding:24px;color:#171653'><h1>MUMO BRANDS</h1><p>Quality and perfection</p><hr/><h2>ORDER / PAYMENT RECEIPT #${d[0]}</h2><p>Customer: ${htmlSafe(d[1])}<br/>Phone: ${htmlSafe(d[2])}</p><p>Product: ${htmlSafe(d[3])}<br/>Size: ${htmlSafe(d[4])} &nbsp; Colour: ${htmlSafe(d[5])}<br/>Quantity: ${htmlSafe(d[6])}<br/>Service: ${htmlSafe(d[7])}<br/>Due date: ${htmlSafe(d[8])}</p><hr/><h3>Total: KES ${d[9]}</h3><h3>Paid: KES ${d[10]}</h3><h3>Balance: KES ${((d[9].toDoubleOrNull()?:0.0)-(d[10].toDoubleOrNull()?:0.0)).coerceAtLeast(0.0)}</h3><p>Status: ${htmlSafe(d[11])}</p><hr/><p>0741 167 612<br/>Bazaar House (Kwa Kaswii), Kitui Stage, Kibwezi Town</p></body></html>""";val web=WebView(activity);web.webViewClient=object:WebViewClient(){override fun onPageFinished(view:WebView,url:String){val pm=activity.getSystemService(Context.PRINT_SERVICE) as PrintManager;pm.print("Mumo-Brands-Order-${d[0]}",view.createPrintDocumentAdapter("Mumo Brands"),PrintAttributes.Builder().build())}};web.loadDataWithBaseURL(null,html,"text/HTML","UTF-8",null)}
fun htmlSafe(s:String)=s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;")

fun menuIcon(label:String)=when(label){"Dashboard"->Icons.Default.Dashboard;"New Orders"->Icons.Default.ReceiptLong;"Customers"->Icons.Default.People;"Clothing Inventory"->Icons.Default.Checkroom;"Printing Services"->Icons.Default.Print;"Payments"->Icons.Default.Payments;"Receipts & Invoices"->Icons.Default.Receipt;"Reports"->Icons.Default.Analytics;else->Icons.Default.Settings}
