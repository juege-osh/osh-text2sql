import axios from 'axios'

export const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
  timeout: 120000
})

http.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error?.code === 'ERR_NETWORK') {
      const target = import.meta.env.VITE_API_BASE_URL || `${window.location.origin}/api`
      error.userMessage = `后端服务不可达，请确认 ${target} 已正确代理到 osh-text2sql backend`
    }
    return Promise.reject(error)
  }
)
