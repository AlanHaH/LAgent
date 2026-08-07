# Java 核心基础

Java 是静态类型、面向对象的编程语言，运行在 Java 虚拟机（JVM）之上。核心设计理念是"一次编写，到处运行"（Write Once, Run Anywhere）：源码编译为字节码（.class），由各平台上的 JVM 解释执行，实现跨平台。

## 1. 语言特性

- **跨平台**：编译产物是平台无关的字节码，JVM 负责适配底层操作系统。
- **自动内存管理**：程序员无需手动释放内存，由垃圾回收器（GC）自动回收不再引用的对象。
- **面向对象**：一切皆对象，支持封装、继承、多态三大特性。
- **强类型**：所有变量在编译期必须声明类型，类型错误在编译期即可发现。
- **丰富生态**：Spring、Maven、JDBC 等成熟框架与工具链。

## 2. 基础语法

### 数据类型
基本类型（8 种）：byte、short、int、long（整数）；float、double（浮点）；char（字符）；boolean（布尔）。
引用类型：类（class）、接口（interface）、数组、枚举（enum）。

```java
int age = 25;              // 基本类型直接存值
String name = "知序";       // 引用类型存对象地址
long big = 10_000_000_000L; // 长整型需加 L 后缀
```

### 运算符与流程控制
- 算术：`+ - * / %`；比较：`== != < >`；逻辑：`&& || !`；三元：`条件 ? 值1 : 值2`
- 分支：`if-else`、`switch`（Java 14+ 支持箭头语法与表达式）
- 循环：`for`、`while`、`do-while`、`for-each`（增强 for 遍历集合）

### 字符串
String 是不可变对象；字符串拼接用 StringBuilder（可变、线程不安全）或 StringBuffer（线程安全）避免频繁创建对象。比较内容用 `equals()`，比较引用地址用 `==`。

## 3. 面向对象

### 类与对象
类是对象的模板，`new` 关键字创建实例。字段（属性）、方法（行为）、构造方法（初始化）。

### 三大特性
- **封装**：字段用 `private` 私有化，通过 `getter/setter` 或方法暴露访问，隐藏实现细节。
- **继承**：子类通过 `extends` 继承父类，复用代码；`super` 调用父类成员；Java 单继承、多实现。
- **多态**：父类引用指向子类对象，运行时根据实际类型调用对应方法（动态绑定）。

### 接口与抽象类
- **抽象类**（abstract）：可以有实现方法，用于"部分实现"的模板；单继承。
- **接口**（interface）：只定义行为契约，Java 8+ 支持 default 默认方法；一个类可实现多个接口。

```java
public interface Study {
    void learn();                  // 抽象方法，实现类必须提供
    default void review() {}       // 默认方法，可选覆盖
}
```

### 修饰符
- 访问控制：`public`（所有）、`protected`（包内+子类）、默认（包内）、`private`（类内）
- 其他：`final`（类不可继承/方法不可重写/变量不可变）、`static`（属于类而非实例）、`abstract`

## 4. 集合框架（java.util）

### 主要接口
- **List**：有序可重复。ArrayList（数组实现，查询快增删慢）、LinkedList（链表实现，增删快）
- **Set**：无序不可重复。HashSet（哈希）、LinkedHashSet（保序）、TreeSet（排序）
- **Map**：键值对。HashMap（哈希表，允许 null）、LinkedHashMap（保序）、TreeMap（有序）、ConcurrentHashMap（线程安全）

### HashMap 原理
数组 + 链表/红黑树。通过 `hash(key) & (length-1)` 计算桶下标；哈希冲突用链表存储，链表长度超过 8 且数组容量 ≥ 64 时树化为红黑树；负载因子默认 0.75，超过阈值扩容为 2 倍。

### equals 与 hashCode 约定
重写 `equals()` 必须同时重写 `hashCode()`：相等的对象 hashCode 必须相等，否则放入 HashSet/HashMap 时无法正确查找。

## 5. 异常处理

- 受检异常（checked）：编译期强制处理，如 IOException、SQLException
- 非受检异常（unchecked）：运行时异常，如 NullPointerException、IllegalArgumentException
- 错误（Error）：JVM 级问题，如 OutOfMemoryError，程序不应捕获

```java
try {
    // 可能抛异常的逻辑
} catch (IOException e) {
    // 处理并恢复或包装重抛
} finally {
    // 无论是否异常都执行（释放资源）
}
// try-with-resources：自动关闭实现了 AutoCloseable 的资源
```

原则：捕获能恢复的异常；不能恢复的尽早抛出；永远不要吞异常（空 catch）。

## 6. 泛型

编译期类型安全机制，`List<String>` 保证列表里只能放字符串，避免运行时 ClassCastException。通配符：`? extends T`（读上限）、`? super T`（写下限）。

## 7. 并发编程

### 线程基础
`Thread` 类或实现 `Runnable`/`Callable` 接口创建线程。`synchronized` 保证方法/代码块的互斥（锁对象），`volatile` 保证可见性（但不保证原子性）。

### 锁
- **synchronized**：内置锁，自动释放
- **ReentrantLock**：可重入、可中断、可公平、可超时
- **乐观锁**：CAS（比较并交换）无锁更新，配合原子类 AtomicInteger 使用

### 线程池
不直接 new Thread，而是用线程池复用线程：`Executors.newFixedThreadPool(n)` 或手动配置 ThreadPoolExecutor（核心线程数、最大线程数、队列、拒绝策略）。线程池参数过高或过低都会导致性能问题。

### 并发容器
ConcurrentHashMap（分段锁/CAS）、CopyOnWriteArrayList（读写分离）、BlockingQueue（生产者-消费者）。

## 8. JVM 基础

### 内存区域（Java 8+）
- **堆（Heap）**：对象实例，GC 主要区域，分为新生代（Eden + 两个 Survivor）和老年代
- **元空间（Metaspace）**：类元数据，本地内存
- **虚拟机栈**：每线程一个，存局部变量、操作数栈
- **程序计数器、本地方法栈**：辅助区域

### 垃圾回收
可达性分析判断对象是否存活（GC Roots 引用链）。常见收集器：CMS（并发标记清除，低停顿）、G1（分区回收，默认）、ZGC（超低停顿）。分代回收：新生代 Minor GC 频繁，存活对象晋升老年代，老年代 Major/Full GC。

### 类加载
双亲委派模型：Bootstrap（JDK 核心）→ Extension/Platform（扩展）→ Application（应用），子加载器优先委托父加载器，保证核心类不被篡改。

### 常用参数
`-Xms` 初始堆、`-Xmx` 最大堆、`-Xss` 栈大小、`-XX:+UseG1GC` 指定收集器、`-XX:+HeapDumpOnOutOfMemoryError` 崩溃时导出堆转储。

## 9. 编程实践要点

- 优先接口编程，依赖注入而非 new 硬编码
- 对象不可变（final 字段 + 不暴露内部引用）降低并发复杂度
- 使用 Optional 表达可空性，避免 NPE
- equals/hashCode 成对重写；覆写 toString 便于调试
- 集合遍历时不要直接增删，用迭代器或收集到新集合
- 字符串拼接大量使用 StringBuilder
- 异常：早抛出（fail-fast）、晚捕获（在能处理的地方处理）
