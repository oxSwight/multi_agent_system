/** @type {import('next').NextConfig} */
const nextConfig = {
  // Required for Docker standalone production build.
  output: 'standalone',

  // Allow cross-origin requests from the Java backend during development.
  async rewrites() {
    return [
      {
        source: '/backend/:path*',
        destination: `${process.env.NEXT_PUBLIC_API_BASE_URL || 'http://localhost:8080'}/:path*`,
      },
    ]
  },
}

export default nextConfig
