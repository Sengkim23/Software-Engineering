# Lab 01, Task 2: Lost-update race

## Why does making only `increment()` synchronized still leave a bug?

`synchronized` gives two guarantees: mutual exclusion and *visibility*. Both
only apply between threads that use the **same lock**.

If `increment()` is synchronized but `get()` is not, then `get()` never
acquires the lock. That causes two problems:

1. **No visibility guarantee.** A reader thread calling `get()` has no
   happens-before relationship with the writers, so it may see a stale value
   from its cache or register instead of the latest one. It can even keep
   seeing the old value forever in a polling loop.
2. **Possible torn reads of a `long`.** The Java memory model allows a
   non-volatile `long` (64 bits) to be read as two 32-bit halves on some
   JVMs, so `get()` could return a value that was never actually written.

The final value looks correct in the benchmark only because `Thread.join()`
creates a happens-before edge. If `get()` is called while the threads are
still running, the result can be wrong.

**Fix:** synchronize `get()` too (same lock), or declare the field
`volatile` (with synchronized `increment()`), or use `AtomicLong`.

# Lab 01, Task 3: Producer-consumer

## What breaks with `notify()` instead of `notifyAll()`

Producers and consumers wait on the SAME monitor, in one wait set.
`notify()` wakes ONE arbitrary waiter, which may be the wrong kind. For
example, a consumer takes an item and calls `notify()`, but the thread woken
is another consumer, not a waiting producer. That consumer sees the buffer is
still empty and waits again, and the producers stay asleep.
Eventually every thread is in `wait()` and nobody is left to notify anyone:
the program hangs (a lost wake-up / deadlock).
Observed in my 20 runs: ___ hangs out of 20.

## What breaks with `if` instead of `while`

After `wait()` returns, the thread has not proven the condition is true.
Another thread can get the lock first and take or add an item, and spurious
wake-ups are also allowed by the JVM. With `if`, the woken thread continues
without re-checking:
- a consumer can call `removeFirst()` on an empty deque and crash with
  `NoSuchElementException`;
- a producer can add to a full buffer and exceed capacity 5.
Observed in my 20 runs: ___.

## Why `while` + `notifyAll()` is correct

`while` re-checks the condition after every wake-up. `notifyAll()` wakes
everyone, so the right thread always gets a turn, and the others simply go
back to waiting. It costs some extra wake-ups.

## BlockingQueue comparison

`ArrayBlockingQueue` replaces the whole `BoundedBuffer` class (about 20 lines
of locking logic) with one `put`/`take` pair. It uses a ReentrantLock with
separate `notFull` and `notEmpty` conditions, so a producer is never woken
when it should not be.

# Lab 01, Task 4: Thread pools, Future and virtual threads

## CPU-bound part
Splitting the range into 16 chunks on a pool of `availableProcessors()` threads
is faster than the single-threaded run, but the speed-up is below the core
count. Trial division costs more for larger numbers, so the chunks near
4 000 000 take longer than the chunks near 0. The last chunks finish late
while other threads sit idle. More, smaller chunks would balance the load better.
The total is identical to the single-threaded result (283146).
Virtual threads would not help here, because the work is pure computation.

## I/O-bound part: platform pool vs virtual threads
Each download only sleeps 200 ms, so a thread is blocked, not working.

- **Fixed pool of 20 platform threads:** only 20 downloads can be in flight.
  500 downloads / 20 threads = 25 rounds x 200 ms = about 5 s. The other tasks
  wait in the queue. A platform thread is an OS thread, so it is expensive, and
  a pool must stay small.
- **Virtual-thread-per-task executor:** every task gets its own virtual thread.
  When a virtual thread blocks in `sleep` (or real network I/O), the JVM
  unmounts it from its carrier thread, and the carrier runs other virtual
  threads. All 500 downloads wait at the same time, so the total is about
  200 ms plus the time spent waiting for the slow task's 1 s timeout (~1.2 s).

Virtual threads help with many blocking tasks. They do not make CPU-bound
code faster.

## Failures, timeouts, shutdown
- `future.get()` rethrows task exceptions wrapped in `ExecutionException`;
  the real error is `getCause()`.
- `get(1, SECONDS)` throws `TimeoutException` for the slow task; then
  `cancel(true)` interrupts it.
- try-with-resources calls `close()`, which waits for running tasks, so the
  JVM exits normally without `System.exit`.

## Bonus: CompletableFuture
`supplyAsync(..., executor)` runs each download, `orTimeout` adds a deadline,
`handle()` turns any failure or timeout into a fallback string, and `allOf`
waits for all of them. Because `handle()` already caught everything, `join()`
afterwards never throws. `orTimeout` starts when the future is created, so it
is only safe with an executor that starts every task immediately (virtual threads).

# Lab 01, Challenge: parallel word-frequency analyser

## Elapsed time (200 files x 50 000 words, my machine: ___ cores)

| Workers            | Elapsed (s) |
|--------------------|-------------|
| 1 platform         |             |
| 2 platform         |             |
| 4 platform         |             |
| 8 platform         |             |
| virtual (--virtual)|             |

(Run each 3 times and take the median. Fill in your real numbers.)

Expected shape: time drops with more workers until the cores or the disk
become the bottleneck. Virtual threads should be about the same as platform
threads, because reading and tokenizing files is CPU/disk work, not waiting.
Virtual threads help when tasks mostly block, not here.

## Why LongAdder / merge beats synchronized on the map

With a `synchronized` map every word takes one global lock, so 8 workers
mostly wait for each other and the program runs close to single-threaded speed,
with extra lock hand-off cost. `ConcurrentHashMap` locks only the bucket being
changed, and `LongAdder` spreads increments over several cells instead of
making all threads CAS the same variable, so contention is much lower.
This design also counts each file in a private map first and merges once per
file, so the shared map is touched about 2 000 times per file instead of
50 000. The reporter reads the counters without any lock, so it does not slow
the workers.

## Coffman conditions

A deadlock needs all four: mutual exclusion, hold-and-wait, no preemption, and
circular wait. This design rules out hold-and-wait and circular wait. No worker
ever holds a resource while waiting for another one: while blocked in
`queue.take()` it holds no lock, and the only locking is inside
`ConcurrentHashMap`/`LongAdder`, which is short and never nested.
The scanner and the workers do wait on each other, but only in opposite
situations: the scanner waits when the queue is full, the workers wait when it
is empty, and the queue cannot be both, so no cycle forms. No-preemption is also
weakened: interruption and the 2 s shutdown limit let main take away any
thread's wait, so nothing blocks forever. Mutual exclusion cannot be removed
for a shared map, but it is confined to very short internal critical sections.