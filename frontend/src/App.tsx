import { Navigate, Route, Routes } from 'react-router-dom';
import AppLayout from './components/AppLayout';
import QueuePage from './pages/QueuePage';
import TasksPage from './pages/TasksPage';
import ReferralsPage from './pages/ReferralsPage';
import ReferralDetailPage from './pages/ReferralDetailPage';
import PatientsPage from './pages/PatientsPage';
import PatientWorkbenchPage from './pages/PatientWorkbenchPage';
import DashboardPage from './pages/DashboardPage';
import LifecyclePage from './pages/LifecyclePage';
import AgentsPage from './pages/AgentsPage';
import { EmptyState } from './components/ui';

export default function App() {
  return (
    <AppLayout>
      <Routes>
        <Route path="/" element={<Navigate to="/queue" replace />} />
        <Route path="/queue" element={<QueuePage />} />
        <Route path="/tasks" element={<TasksPage />} />
        <Route path="/lifecycle" element={<LifecyclePage />} />
        <Route path="/referrals" element={<ReferralsPage />} />
        <Route path="/referrals/:referralId" element={<ReferralDetailPage />} />
        <Route path="/patients" element={<PatientsPage />} />
        <Route path="/patients/:patientId" element={<PatientWorkbenchPage />} />
        <Route path="/dashboard" element={<DashboardPage />} />
        <Route path="/agents" element={<AgentsPage />} />
        <Route path="*" element={<EmptyState message="Page not found." />} />
      </Routes>
    </AppLayout>
  );
}
