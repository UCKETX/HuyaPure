import SwiftUI
import WebKit

/// Native `WKWebView` modal for Huya UDB SafeAuth verification (`udbSafeAuth` postMessage capture).
struct SafeAuthSheetView: View {
    let urlString: String
    let title: String
    let promptMessage: String
    let onAuthSuccess: (String) -> Void
    let onCancel: () -> Void

    @State private var isLoading = true
    @State private var reloadToken = UUID()

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                if !promptMessage.isEmpty {
                    HStack(spacing: 8) {
                        Image(systemName: "checkmark.shield.fill")
                            .foregroundColor(HubanTheme.accent)
                        Text(promptMessage)
                            .font(.footnote)
                            .foregroundColor(.secondary)
                            .lineLimit(2)
                        Spacer(minLength: 0)
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .background(HubanTheme.cardSecondary)
                }

                if isLoading {
                    ProgressView()
                        .progressViewStyle(.linear)
                        .tint(HubanTheme.accent)
                }

                if let url = URL(string: urlString) {
                    SafeAuthWebView(
                        url: url,
                        reloadToken: reloadToken,
                        isLoading: $isLoading,
                        onAuthIdCaptured: onAuthSuccess
                    )
                } else {
                    VStack(spacing: 12) {
                        Image(systemName: "exclamationmark.triangle")
                            .font(.largeTitle)
                            .foregroundColor(HubanTheme.warning)
                        Text("安全验证链接无效")
                            .font(.headline)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            }
            .navigationTitle(title.isEmpty ? "虎牙安全验证" : title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消", action: onCancel)
                }
                ToolbarItem(placement: .primaryAction) {
                    Button {
                        reloadToken = UUID()
                    } label: {
                        Image(systemName: "arrow.clockwise")
                    }
                }
            }
        }
    }
}

private struct SafeAuthWebView: UIViewRepresentable {
    let url: URL
    let reloadToken: UUID
    @Binding var isLoading: Bool
    let onAuthIdCaptured: (String) -> Void

    static let handlerName = "hubanSafeAuth"

    /// Captures Huya's `udbSafeAuth` event both from `window.addEventListener("message", ...)`
    /// and from direct `window.parent.postMessage` / `window.postMessage` calls when hosted top-level.
    private static let captureScriptSource = """
    (function() {
      if (window.__hubanSafeAuthHooked) return;
      window.__hubanSafeAuthHooked = true;
      var reported = false;
      function inspectPayload(raw) {
        if (reported || !raw) return;
        var msg = raw;
        if (typeof msg === 'string') {
          try { msg = JSON.parse(msg); } catch (e) { return; }
        }
        if (!msg || typeof msg !== 'object') return;
        if (msg.eventType === 'udbSafeAuth') {
          var authId = '';
          if (msg.param && msg.param.data && msg.param.data.authId) {
            authId = String(msg.param.data.authId);
          } else if (msg.data && msg.data.authId) {
            authId = String(msg.data.authId);
          } else if (msg.authId) {
            authId = String(msg.authId);
          }
          reported = true;
          try {
            window.webkit.messageHandlers.hubanSafeAuth.postMessage({
              eventType: 'udbSafeAuth',
              authId: authId
            });
          } catch (e) {}
        }
      }
      window.addEventListener('message', function(ev) {
        inspectPayload(ev && ev.data);
      }, false);
      var origPost = window.postMessage;
      if (typeof origPost === 'function') {
        window.postMessage = function(data) {
          inspectPayload(data);
          return origPost.apply(this, arguments);
        };
      }
    })();
    """

    func makeCoordinator() -> Coordinator {
        Coordinator(isLoading: $isLoading, onAuthIdCaptured: onAuthIdCaptured)
    }

    func makeUIView(context: Context) -> WKWebView {
        let controller = WKUserContentController()
        let script = WKUserScript(
            source: Self.captureScriptSource,
            injectionTime: .atDocumentStart,
            forMainFrameOnly: false
        )
        controller.addUserScript(script)
        controller.add(WeakScriptMessageHandler(delegate: context.coordinator), name: Self.handlerName)

        let config = WKWebViewConfiguration()
        config.userContentController = controller
        config.allowsInlineMediaPlayback = true
        config.websiteDataStore = .nonPersistent()

        let webView = WKWebView(frame: .zero, configuration: config)
        webView.navigationDelegate = context.coordinator
        webView.allowsBackForwardNavigationGestures = false
        context.coordinator.lastReloadToken = reloadToken
        webView.load(URLRequest(url: url))
        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {
        if context.coordinator.lastReloadToken != reloadToken {
            context.coordinator.lastReloadToken = reloadToken
            context.coordinator.didDeliver = false
            webView.load(URLRequest(url: url))
        }
    }

    static func dismantleUIView(_ uiView: WKWebView, coordinator: Coordinator) {
        uiView.configuration.userContentController.removeScriptMessageHandler(forName: handlerName)
        uiView.navigationDelegate = nil
    }

    final class Coordinator: NSObject, WKNavigationDelegate, WKScriptMessageHandler {
        @Binding var isLoading: Bool
        let onAuthIdCaptured: (String) -> Void
        var lastReloadToken: UUID = UUID()
        var didDeliver = false

        init(isLoading: Binding<Bool>, onAuthIdCaptured: @escaping (String) -> Void) {
            self._isLoading = isLoading
            self.onAuthIdCaptured = onAuthIdCaptured
        }

        func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
            DispatchQueue.main.async { self.isLoading = true }
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            DispatchQueue.main.async { self.isLoading = false }
        }

        func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
            DispatchQueue.main.async { self.isLoading = false }
        }

        func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
            guard message.name == SafeAuthWebView.handlerName, !didDeliver else { return }
            guard let dict = message.body as? [String: Any],
                  (dict["eventType"] as? String) == "udbSafeAuth" else {
                return
            }
            let authId = (dict["authId"] as? String) ?? ""
            didDeliver = true
            DispatchQueue.main.async {
                self.onAuthIdCaptured(authId)
            }
        }
    }
}

private final class WeakScriptMessageHandler: NSObject, WKScriptMessageHandler {
    weak var delegate: WKScriptMessageHandler?

    init(delegate: WKScriptMessageHandler) {
        self.delegate = delegate
    }

    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        delegate?.userContentController(userContentController, didReceive: message)
    }
}
