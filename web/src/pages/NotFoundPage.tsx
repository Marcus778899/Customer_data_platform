import { AppLink } from '../components/AppLink'

export function NotFoundPage() {
  return (
    <div className="card msg">
      <h1 className="h1-card">找不到這個頁面</h1>
      <p>
        <AppLink to="/">回到查詢</AppLink>
      </p>
    </div>
  )
}
