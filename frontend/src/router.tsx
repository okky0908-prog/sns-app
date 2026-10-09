import { createBrowserRouter, Navigate } from 'react-router'
import { PublicOnly, RequireAuth } from './auth/RouteGuards'
import { AppLayout } from './components/AppLayout'
import { LoginPage } from './pages/LoginPage'
import { FollowListRoute } from './pages/FollowListPage'
import { PostDetailPage } from './pages/PostDetailPage'
import { ProfileRoute } from './pages/ProfilePage'
import { SignupPage } from './pages/SignupPage'
import { TimelinePage } from './pages/TimelinePage'

// 画面の URL は docs/screens.md の画面一覧に合わせる
export const router = createBrowserRouter([
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
        ],
      },
    ],
  },
  { path: '*', element: <Navigate to="/" replace /> },
])
