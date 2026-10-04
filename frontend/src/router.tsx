import { createBrowserRouter, Navigate } from 'react-router'
import { PublicOnly, RequireAuth } from './auth/RouteGuards'
import { HomePage } from './pages/HomePage'
import { LoginPage } from './pages/LoginPage'
import { SignupPage } from './pages/SignupPage'

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
    // ログインが必要な画面
    element: <RequireAuth />,
    children: [{ path: '/', element: <HomePage /> }],
  },
  { path: '*', element: <Navigate to="/" replace /> },
])
