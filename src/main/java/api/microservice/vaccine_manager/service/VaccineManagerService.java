package api.microservice.vaccine_manager.service;

import api.microservice.vaccine_manager.client.PatientClient;
import api.microservice.vaccine_manager.client.VaccineClient;
import api.microservice.vaccine_manager.dto.Patient;
import api.microservice.vaccine_manager.dto.Vaccine;
import api.microservice.vaccine_manager.dto.VaccineManagerDTO;
import api.microservice.vaccine_manager.entity.VaccineManager;
import api.microservice.vaccine_manager.handler.exceptions.InvalidVaccineDateException;
import api.microservice.vaccine_manager.handler.exceptions.BadRequestException;
import api.microservice.vaccine_manager.handler.exceptions.NotFoundException;
import api.microservice.vaccine_manager.repository.VaccineManagerRepository;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class VaccineManagerService {

    @Autowired
    private VaccineClient vaccineClient;

    @Autowired
    private PatientClient patientClient;

    @Autowired
    private VaccineManagerRepository vaccineManagerRepository;

    public VaccineManager create(VaccineManager vaccineManager) {
        VaccineManager newVaccineManager = new VaccineManager();
        Optional<Patient> patientOptional = patientClient.getByIdPatient(vaccineManager.getIdPatient());
        if (patientOptional.isPresent()) {
            String idPatient = patientOptional.get().getId();
            newVaccineManager.setIdPatient(idPatient);
        }
        Optional<Vaccine> vaccineOptional = vaccineClient.getByIdVaccine(vaccineManager.getIdVaccine());

        newVaccineManager.setVaccineDate(vaccineManager.getVaccineDate());

        vaccineManager.getListOfDoses().add(newVaccineManager.getVaccineDate());
        newVaccineManager.setListOfDoses(vaccineManager.getListOfDoses());

        if (vaccineOptional.isPresent()) {
            Vaccine vaccine = vaccineOptional.get();
            String idVaccine = vaccine.getId();
            newVaccineManager.setIdVaccine(idVaccine);
        }

        newVaccineManager.setIdVaccine(vaccineManager.getIdVaccine());
        newVaccineManager.setNurseProfessional(vaccineManager.getNurseProfessional());
        return vaccineManagerRepository.insert(newVaccineManager);
    }

    public List<VaccineManagerDTO> listVaccineManager(String state) {
        List<VaccineManager> listOfVaccineManger = vaccineManagerRepository.findAll();
        return filterVaccineManager(state, listOfVaccineManger);
    }

    private List<VaccineManagerDTO> filterVaccineManager(String state, List<VaccineManager> listOfVaccineManger) {
        List<VaccineManagerDTO> listOfVaccineManagerDTO = new ArrayList<>();

        listOfVaccineManger.forEach(item -> {
            VaccineManagerDTO managerDTO = new VaccineManagerDTO();
            BeanUtils.copyProperties(item, managerDTO);

            Optional<Vaccine> vaccine = vaccineClient.getByIdVaccine(item.getIdVaccine());
            vaccine.ifPresent(managerDTO::setVaccine);

            Optional<Patient> patient = patientClient.getByIdPatient(item.getIdPatient());

            LocalDate lastVaccine = managerDTO.getListOfDoses().get(managerDTO.getListOfDoses().size() - 1);
            if (
                    patient.isEmpty()
                            || (!state.isEmpty()
                                && !patient.get().getAddress().getState().equalsIgnoreCase(state))
//                            || lastVaccine.plusDays(vaccine.get().getIntervalBetweenDoses()).isAfter(LocalDate.now())
            ) {
                return;
            }

            managerDTO.setPatient(patient.get());
            listOfVaccineManagerDTO.add(managerDTO);
        });

        return listOfVaccineManagerDTO;
    }

    public VaccineManagerDTO update(String id, VaccineManager vaccineManager) throws InvalidVaccineDateException, NotFoundException, BadRequestException {
        Optional<VaccineManager> storedVaccineManagerOptional = vaccineManagerRepository.findById(id);

        if (storedVaccineManagerOptional.isEmpty()) {
            throw new NotFoundException("Registro da vacinação não foi encontrado.");
        }

        VaccineManager storedVaccineManager = storedVaccineManagerOptional.get();
        Optional<Vaccine> vaccineOptional = vaccineClient.getByIdVaccine(vaccineManager.getIdVaccine());
        Optional<Vaccine> oldVaccineOptional = vaccineClient.getByIdVaccine(storedVaccineManager.getIdVaccine());
        Optional<Patient> patientOptional = patientClient.getByIdPatient(vaccineManager.getIdPatient());

        if (vaccineOptional.isEmpty() || !(vaccineOptional.get() instanceof Vaccine)) {
            throw new NotFoundException("Vacina não encontrada");
        } else if (patientOptional.isEmpty()) {
            throw new NotFoundException("Paciente não encontrado");
        } else if (oldVaccineOptional.isEmpty()) {
            throw new NotFoundException("Vacina antiga não encontrada");
        }

        if (storedVaccineManager.getListOfDoses().size() <= 0) {
            throw new BadRequestException("Você não possui registros a serem removidos.");
        }

        Vaccine vaccine = vaccineOptional.get();
        Integer vaccineInterval = vaccine.getIntervalBetweenDoses();
        LocalDate vaccineValidate = vaccine.getValidateDate();
        int lastAmountOfDoses = storedVaccineManager.getListOfDoses().size() - 1;
        LocalDate lastVacinationPlusDays = storedVaccineManager.getListOfDoses().get(lastAmountOfDoses).plusDays(vaccineInterval);
        LocalDate vaccineDate = vaccineManager.getVaccineDate();

        if (!oldVaccineOptional.get().getManufacturer().equals(vaccine.getManufacturer())) {
            throw new BadRequestException("A vacina não pode ser de fabricantes diferentes.");
        }

        if (
            LocalDate.now().isAfter(vaccineValidate)
            || (vaccineDate.isBefore(lastVacinationPlusDays))
        ) throw new InvalidVaccineDateException();

        if (storedVaccineManager.getListOfDoses().size() >= vaccine.getAmountOfDose()) {
            throw new InvalidVaccineDateException();
        }

        storedVaccineManager.setVaccineDate(vaccineManager.getVaccineDate());
        storedVaccineManager.setNurseProfessional(vaccineManager.getNurseProfessional());

        if (!vaccineDate.isEqual(lastVacinationPlusDays)) {
            storedVaccineManager.getListOfDoses().add(vaccineManager.getVaccineDate());
        }

        VaccineManagerDTO vaccineManagerDTO = new VaccineManagerDTO();
        BeanUtils.copyProperties(storedVaccineManager, vaccineManagerDTO);

        vaccineManagerDTO.setPatient(patientOptional.get());
        vaccineManagerDTO.setVaccine(vaccineOptional.get());

        vaccineManagerRepository.save(storedVaccineManager);
        return vaccineManagerDTO;
    }

    public VaccineManager removeLastVaccination(String id) throws NotFoundException, BadRequestException {
        Optional<VaccineManager> vaccineManagerOptional = vaccineManagerRepository.findById(id);

        if (vaccineManagerOptional.isEmpty()) {
            throw new NotFoundException("Registro da vacinação não foi encontrado.");
        }
        VaccineManager vaccineManager = vaccineManagerOptional.get();

        int lastVaccineDose = vaccineManager.getListOfDoses().size();

        if (lastVaccineDose <= 0) {
            throw new BadRequestException("Você não possui registros a serem removidos.");
        }

        vaccineManager.getListOfDoses().remove(lastVaccineDose - 1);

        return  vaccineManagerRepository.save(vaccineManager);
    }
}
