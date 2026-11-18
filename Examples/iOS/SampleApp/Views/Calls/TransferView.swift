import SwiftUI
import BaresipKit

/// Hands the call to someone else: ask them first while the call waits, or send it on at once.
struct TransferView: View {
    @EnvironmentObject private var model: CallModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if let held = model.heldRow, let current = model.currentRow {
                    consulting(held: held, current: current)
                } else {
                    choosing
                }
            }
            .padding(16)
        }
        .scrollDismissesKeyboard(.interactively)
        .background(Theme.bar.ignoresSafeArea())
        .navigationTitle("Transfer the call")
        .navigationBarTitleDisplayMode(.inline)
    }

    @ViewBuilder
    private var choosing: some View {
        Note(text: "\(model.currentRow?.title ?? "The call") waits on hold while you ask.")
        StackedField(title: "Transfer to", text: $model.transferTarget, prompt: "Number or address",
                     keyboard: .asciiCapable)
            .accessibilityIdentifier("transfer.target")
        let choices = transferChoices
        choiceSection("Doors", choices.doors)
        choiceSection("Contacts", choices.contacts)
        choiceSection("Recent", choices.recent)
        VStack(alignment: .leading, spacing: 6) {
            Button("Ask \(targetName) first") {
                Task { await model.consult() }
            }
            .buttonStyle(FilledButtonStyle(kind: .light, height: 56))
            Note(text: "You talk to \(targetName), then hand the call over or come back.")
        }
        .disabled(model.transferTarget.trimmed.isEmpty)
        .padding(.top, 8)
        VStack(alignment: .leading, spacing: 6) {
            Button("Transfer now") {
                Task {
                    await model.blindTransfer()
                    dismiss()
                }
            }
            .buttonStyle(FilledButtonStyle(kind: .raised))
            Note(text: "The call rings \(targetName) and you leave it at once.")
        }
        .disabled(model.transferTarget.trimmed.isEmpty)
    }

    @ViewBuilder
    private func consulting(held: CallRow, current: CallRow) -> some View {
        Note(text: "You are asking \(current.title). \(held.title) hears nothing.")
        party(current, status: Theme.info, highlighted: true)
        party(held, status: Theme.secondary, highlighted: false)
        Button("Hand \(held.title) to \(current.title)") {
            Task {
                await model.completeTransfer()
                dismiss()
            }
        }
        .buttonStyle(FilledButtonStyle(kind: .light, height: 56))
        .padding(.top, 8)
        Button("End this and return to \(held.title)") {
            Task {
                await model.endConsultation()
                dismiss()
            }
        }
        .buttonStyle(FilledButtonStyle(kind: .raised))
    }

    private func party(_ row: CallRow, status: Color, highlighted: Bool) -> some View {
        Card(highlighted: highlighted) {
            HStack(spacing: 12) {
                Avatar(initials: row.initials, size: 48)
                VStack(alignment: .leading, spacing: 3) {
                    Text(row.title)
                        .font(.display(22))
                        .foregroundStyle(Theme.text)
                    TimelineView(.periodic(from: .now, by: 1)) { context in
                        Text(callStatusText(row, now: context.date))
                            .textCase(.uppercase)
                            .font(.footnote.weight(.semibold))
                            .monospacedDigit()
                            .foregroundStyle(status)
                    }
                }
            }
            .padding(14)
        }
    }

    private var targetName: String {
        let target = model.transferTarget.trimmed
        return target.isEmpty ? "them" : CallRow.displayName(target)
    }

    private var transferChoices: (doors: [TransferChoice], contacts: [TransferChoice], recent: [TransferChoice]) {
        var seen: Set<String> = [addressKey(model.currentRow?.uri ?? "")]
        func unique(_ list: some Sequence<TransferChoice>, limit: Int = .max) -> [TransferChoice] {
            var result: [TransferChoice] = []
            for choice in list {
                if result.count == limit {
                    break
                }
                if seen.insert(choice.key).inserted {
                    result.append(choice)
                }
            }
            return result
        }
        func choice(_ title: String, _ detail: String, _ address: String) -> TransferChoice {
            TransferChoice(title: title, detail: detail, address: address, key: addressKey(address))
        }
        let doors = unique(model.doors.lazy.filter { !$0.address.trimmed.isEmpty }.map {
            choice($0.title, $0.user, $0.address.trimmed)
        })
        let contacts = unique(model.contacts.lazy.filter { !$0.address.trimmed.isEmpty }.map {
            choice($0.title, CallRow.displayName($0.address.trimmed), $0.address.trimmed)
        })
        let recent = unique(model.history.lazy.filter { !$0.uri.isEmpty }.map {
            choice($0.name, $0.user, $0.uri)
        }, limit: 4)
        return (doors, contacts, recent)
    }

    private func addressKey(_ address: String) -> String {
        var value = model.normalizedDestination(address)
        for scheme in ["sips:", "sip:"] where value.hasPrefix(scheme) {
            value = String(value.dropFirst(scheme.count))
        }
        return value.prefix { $0 != ";" && $0 != "?" }.lowercased()
    }

    @ViewBuilder
    private func choiceSection(_ title: String, _ list: [TransferChoice]) -> some View {
        if !list.isEmpty {
            TitledSection(title: title) {
                Card {
                    ForEach(Array(list.enumerated()), id: \.element.key) { index, choice in
                        if index > 0 {
                            RowDivider()
                        }
                        choiceRow(choice)
                    }
                }
            }
        }
    }

    private func choiceRow(_ choice: TransferChoice) -> some View {
        Button {
            model.transferTarget = choice.address
        } label: {
            HStack(spacing: 12) {
                Text(choice.title)
                    .foregroundStyle(Theme.text)
                Spacer()
                Text(choice.detail)
                    .font(.subheadline)
                    .foregroundStyle(Theme.secondary)
                if model.transferTarget == choice.address {
                    Image(systemName: "checkmark")
                        .foregroundStyle(Theme.info)
                        .accessibilityLabel("Chosen")
                }
            }
            .padding(.horizontal, 16)
            .frame(minHeight: 52)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

private struct TransferChoice {
    let title: String
    let detail: String
    let address: String
    let key: String
}
