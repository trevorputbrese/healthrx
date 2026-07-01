import { Navigate, Route, Routes } from 'react-router-dom';
import AppLayout from './components/AppLayout';
import QueuePage from './pages/QueuePage';
import ReferralDetailPage from './pages/ReferralDetailPage';
import PatientWorkbenchPage from './pages/PatientWorkbenchPage';
import DashboardPage from './pages/DashboardPage';
import LifecyclePage from './pages/LifecyclePage';
import { EmptyState } from './components/ui';

export default function App() {
  return (
    <AppLayout>
      <Routes>
        <Route path="/" element={<Navigate to="/queue" replace />} />
        <Route path="/queue" element={<QueuePage />} />
        <Route path="/lifecycle" element={<LifecyclePage />} />
        <Route path="/referrals/:referralId" element={<ReferralDetailPage />} />
        <Route path="/patients/:patientId" element={<PatientWorkbenchPage />} />
        <Route path="/dashboard" element={<DashboardPage />} />
        <Route path="*" element={<EmptyState message="Page not found." />} />
      </Routes>
    </AppLayout>
  );
}
