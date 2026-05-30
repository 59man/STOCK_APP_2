import { Component, ReactNode, ErrorInfo } from 'react'

interface Props { children: ReactNode; fallback?: ReactNode }
interface State { hasError: boolean; message: string }

export class ErrorBoundary extends Component<Props, State> {
  constructor(props: Props) {
    super(props)
    this.state = { hasError: false, message: '' }
  }

  static getDerivedStateFromError(err: unknown): State {
    return { hasError: true, message: err instanceof Error ? err.message : String(err) }
  }

  override componentDidCatch(err: Error, info: ErrorInfo) {
    console.error('[ErrorBoundary] caught:', err.message, info.componentStack)
  }

  override render() {
    if (this.state.hasError) {
      if (this.props.fallback) return this.props.fallback
      return (
        <div style={{ padding: 24, color: '#ef4444' }}>
          <strong>Something went wrong</strong>
          <pre style={{ marginTop: 8, fontSize: 12, color: '#aaa', whiteSpace: 'pre-wrap' }}>
            {this.state.message}
          </pre>
          <button
            style={{ marginTop: 12, padding: '6px 16px', background: '#1e1e2e', border: '1px solid #444', color: '#e2e8f0', borderRadius: 4, cursor: 'pointer' }}
            onClick={() => this.setState({ hasError: false, message: '' })}
          >
            Retry
          </button>
        </div>
      )
    }
    return this.props.children
  }
}
