import { createBrowserRouter, Navigate, type RouteObject } from 'react-router'
import { PublicOnly, RequireAuth } from './auth/RouteGuards'
import { AppLayout } from './components/AppLayout'
import { ErrorPage } from './pages/ErrorPage'
import { LoginPage } from './pages/LoginPage'
import { FollowListRoute } from './pages/FollowListPage'
import { PostDetailPage } from './pages/PostDetailPage'
import { ProfileEditPage } from './pages/ProfileEditPage'
import { SearchRoute } from './pages/SearchPage'
import { ProfileRoute } from './pages/ProfilePage'
import { SignupPage } from './pages/SignupPage'
import { TimelinePage } from './pages/TimelinePage'

// 画面の URL は docs/screens.md の画面一覧に合わせる。
// 一覧（routes）とブラウザ用の切り替え（router）を分けているのは、テストで同じ一覧から好きな URL で始められるようにするため（routes.test.tsx）
export const routes: RouteObject[] = [
  {
    // どの画面で表示中のエラーが起きても、真っ白な画面の代わりに ErrorPage を出し、エラーをサーバーに送る
    errorElement: <ErrorPage />,
    children: [
      {
        // 未ログインの人だけが開ける画面
        element: <PublicOnly />,
        children: [
          { path: '/login', element: <LoginPage /> },
          { path: '/signup', element: <SignupPage /> },
        ],
      },
      {
        // ログインが必要な画面（共通ヘッダー付き）
        element: <RequireAuth />,
        children: [
          {
            element: <AppLayout />,
            children: [
              // タブを切り替えたら画面ごと作り直して1ページ目から読み込む（key がないと前のタブの状態が残る）
              { path: '/', element: <TimelinePage key="following" tab="following" /> },
              { path: '/all', element: <TimelinePage key="all" tab="all" /> },
              { path: '/posts/:postId', element: <PostDetailPage /> },
              { path: '/users/:username', element: <ProfileRoute /> },
              { path: '/users/:username/following', element: <FollowListRoute kind="following" /> },
              { path: '/users/:username/followers', element: <FollowListRoute kind="followers" /> },
              { path: '/settings/profile', element: <ProfileEditPage /> },
              { path: '/search', element: <SearchRoute /> },
            ],
          },
        ],
      },
      { path: '*', element: <Navigate to="/" replace /> },
    ],
  },
]

export const router = createBrowserRouter(routes)
