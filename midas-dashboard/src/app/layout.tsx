import type { Metadata } from 'next'
import './globals.css'
import Sidebar from '@/components/layout/Sidebar'

export const metadata: Metadata = {
  title:       'MIDAS D3 — Pipeline Dashboard',
  description: 'Real-time monitoring dashboard for the MIDAS D3 agentic pipeline.',
}

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="ru" className="dark">
      <body className="flex h-screen overflow-hidden bg-[#080812] text-slate-200 antialiased">
        <Sidebar />
        <main className="flex-1 overflow-y-auto">
          {children}
        </main>
      </body>
    </html>
  )
}
