import Foundation

private struct Entry: Identifiable, Equatable {
    let id: Int
    var subtitle: String?
}

@main struct AirPlayQueueStateChecks {
    static func main() {
        var queue = AirPlayQueueState<Entry>()
        precondition(queue.current == nil && queue.upcoming.isEmpty)
        precondition(queue.advance() == nil)

        queue.enqueue(Entry(id: 1))
        queue.enqueue(Entry(id: 2))
        queue.enqueue(Entry(id: 3))
        precondition(queue.current?.id == 1)
        precondition(queue.upcoming.map(\.id) == [2, 3])
        queue.replaceCurrent(Entry(id: 1, subtitle: "English"))
        precondition(queue.current?.subtitle == "English")
        precondition(queue.upcoming.map(\.id) == [2, 3], "Late subtitle attachment preserves upcoming entries")
        precondition(queue.advance()?.id == 2)
        precondition(queue.advance()?.id == 3)
        precondition(queue.advance() == nil && queue.current == nil)
        precondition(queue.advance() == nil, "Repeated end notifications remain exhausted")

        queue.play(Entry(id: 0))
        for id in 1...5 { queue.enqueue(Entry(id: id)) }
        queue.move(from: IndexSet([1, 3]), to: 5)
        precondition(queue.upcoming.map(\.id) == [1, 3, 5, 2, 4], "Disjoint moves retain relative order")
        queue.move(from: IndexSet([3, 4]), to: 0)
        precondition(queue.upcoming.map(\.id) == [2, 4, 1, 3, 5])
        queue.move(from: IndexSet([0, 1]), to: 1)
        precondition(queue.upcoming.map(\.id) == [2, 4, 1, 3, 5], "A move into its own range is stable")
        queue.move(from: IndexSet([99]), to: 0)
        queue.move(from: IndexSet(), to: 0)
        precondition(queue.upcoming.map(\.id) == [2, 4, 1, 3, 5])
        queue.move(from: IndexSet([0]), to: 100)
        precondition(queue.upcoming.map(\.id) == [4, 1, 3, 5, 2])
        queue.move(from: IndexSet([4]), to: -1)
        precondition(queue.upcoming.map(\.id) == [2, 4, 1, 3, 5])
        queue.remove(0)
        queue.remove(99)
        precondition(queue.current?.id == 0 && queue.upcoming.count == 5)
        queue.remove(1)
        precondition(queue.upcoming.map(\.id) == [2, 4, 3, 5])
        queue.clearUpcoming()
        precondition(queue.current?.id == 0 && queue.upcoming.isEmpty)
        queue.enqueue(Entry(id: 6))
        queue.play(Entry(id: 7))
        precondition(queue.current?.id == 7 && queue.upcoming.isEmpty, "Play now replaces the complete queue")
        queue.enqueue(Entry(id: 8))
        queue.stop()
        precondition(queue.current == nil && queue.upcoming.isEmpty)
        queue.enqueue(Entry(id: 9))
        precondition(queue.current?.id == 9 && queue.upcoming.isEmpty)
        print("AirPlay queue checks passed")
    }
}
